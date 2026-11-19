"""Isolated acceptance scenarios for the packaged application, using synthetic PDFs."""
from http.client import HTTPConnection, RemoteDisconnected
from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait
from http.server import BaseHTTPRequestHandler, HTTPServer
from io import BytesIO
import json
from statistics import median
from threading import Condition, Thread
from time import monotonic, sleep
from urllib.error import HTTPError
from urllib.parse import urlencode
from uuid import UUID, uuid4

from reportlab.pdfgen.canvas import Canvas


FILES, PAGES, PAGE_DELAY = 6, 8, .20
API = '/api/v1'


def check(value, message):
    if not value:
        raise AssertionError(message)


def until(probe, seconds, label):
    deadline = monotonic() + seconds
    while monotonic() < deadline:
        answer = probe()
        if answer:
            return answer
        sleep(.10)
    raise TimeoutError(label)


class PdfModel:
    def __init__(self):
        self.cv = Condition()
        self.in_flight = 0
        self.drop_marker = None
        self.drop_calls = 0

    def respond(self, text):
        with self.cv:
            if self.drop_marker and self.drop_marker in text:
                self.drop_calls += 1
                if self.drop_calls == 1: return 'drop'
        if 'BULK-PDF' in text:
            with self.cv:
                self.in_flight += 1
                self.cv.notify_all()
            try:
                sleep(PAGE_DELAY)
            finally:
                with self.cv:
                    self.in_flight -= 1
        return 200

    def wait_for_active(self, target):
        with self.cv:
            check(self.cv.wait_for(lambda: self.in_flight >= target, timeout=12),
                  'Required number of PDF embeddings did not enter service')


def upload_pdf(api, project, version):
    buffer = BytesIO()
    pdf = Canvas(buffer, pageCompression=0, invariant=True)
    for page in range(1, PAGES+1):
        pdf.drawString(50, 720, f'SYNTHETIC BULK-PDF SYN-R100 {version} PAGE-{page:02d}')
        pdf.showPage()
    pdf.save()
    binary = buffer.getvalue()
    boundary = 'pilot'+uuid4().hex
    fields = {'project_id': project, 'version': version, 'doc_type':'spec',
              'material_model':'SYN-R100','classification':'internal'}
    parts = [f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode()
             for k,v in fields.items()]
    parts.extend([f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{version}.pdf"\r\nContent-Type: application/pdf\r\n\r\n'.encode(),
                  binary, f'\r\n--{boundary}--\r\n'.encode()])
    doc = api(API+'/documents/upload', raw=b''.join(parts),
              headers={'Content-Type':'multipart/form-data; boundary='+boundary})
    return str(UUID(doc['id'])), len(binary)


def dropped_response(submit, payload):
    # One loopback request, one fixed upstream operation. Read the accepted response,
    # then close the client connection without sending headers or a body.
    observed = {}

    class Drop(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            try:
                actual = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                check(self.path == '/submit' and actual == payload, 'Unexpected proxy request')
                observed['batch'] = submit(actual)
            except Exception as exc:
                observed['error'] = repr(exc)
            finally:
                self.close_connection = True

    with HTTPServer(('127.0.0.1',0), Drop) as server:
        server.timeout = 12
        handler = Thread(target=server.handle_request,daemon=True)
        handler.start()
        client = HTTPConnection('127.0.0.1',server.server_port,timeout=15)
        try:
            client.request('POST','/submit',json.dumps(payload),{'Content-Type':'application/json'})
            try:
                client.getresponse()
            except RemoteDisconnected:
                pass
            else:
                raise AssertionError('Client unexpectedly received a response')
        finally:
            client.close()
            handler.join(15)
    check(not handler.is_alive() and 'batch' in observed, f'No upstream 202: {observed.get("error")}')
    return observed['batch']


def run_contention(model, api, upload, sql, schema, tag, result, experiment=None):
    bulk = str(UUID(api(API+'/projects', {'name': 'SYNTHETIC PDF import '+tag})['id']))
    short = 'demo-project'
    token = api(API+'/auth/login', {'username':'designer','password':'local-demo-change-me'})['access_token']
    auth = {'Authorization':'Bearer '+token}
    provider = PdfModel()
    model.mixed_model = provider
    result['workload'] = {'rounds_per_mode':3,'bulk_files_per_contended_round':FILES,'pages_per_pdf':PAGES,
                          'extra_ms_per_pdf_embedding':int(PAGE_DELAY*1000),'consumers':2,'prefetch':2,
                          'claim_max_run_ms':120000,'poll_interval_ms':100,'urgent_files_per_round':1}

    def submit(payload):
        return api(API+'/ingest-batches',payload,headers=auth if payload['project_id']==short else None)

    def wait_batch(bid, project):
        def poll():
            row = api(API+'/ingest-batches/'+bid,headers=auth if project==short else None)
            if row['finished']==row['total']:
                check(row['status']=='completed',f'Batch {bid} terminated: {row["counts"]}')
                return row
        return until(poll,40,'Batch did not finish: '+bid)

    def specs(project,version):
        path=API+'/specifications?'+urlencode({'project_id':project,'material_model':'SYN-R100','version':version})
        return api(path,headers=auth if project==short else None)['results']

    def audit(ids, expected_pages, attempts=1):
        quoted=','.join("'"+str(UUID(doc))+"'" for doc in ids)
        rows=sql(f"SELECT t.target_id,t.attempt,t.status,o.status,count(e.id),count(v.evidence_id) "
                 f"FROM {schema}.mb_tasks t JOIN {schema}.mb_task_outbox o ON t.id=o.task_id "
                 f"LEFT JOIN {schema}.mb_evidence e ON t.target_id=e.document_id "
                 f"LEFT JOIN {schema}.mb_vectors v ON e.id=v.evidence_id WHERE t.target_id IN ({quoted}) "
                 "GROUP BY t.id,t.target_id,t.attempt,t.status,o.status")
        actual=[line.split('|') for line in rows.splitlines()]
        expected=[str(attempts),'completed','done',str(expected_pages),str(expected_pages)]
        check(sorted(actual)==sorted([[doc,*expected] for doc in ids]),
              f'Task/attempt/evidence/vector reconciliation failed: expected per file {expected}; observed {actual}')

    def measure(name, mode, monitor):
        loaded = mode != 'idle'
        version='urgent-'+name+'-'+tag
        doc=upload(short,version)
        papers={}
        pdf_bytes=0
        if loaded:
            for i in range(FILES):
                rev=f'heavy-{name}-{i}'
                paper,nbytes=upload_pdf(api,bulk,rev)
                papers[paper]=rev
                pdf_bytes+=nbytes
            def run_bulk():
                start_bulk = monotonic()
                def group(i,ids):
                    bulk_batch=submit({'project_id':bulk,'document_ids':ids,'request_id':f'bulk-{name}-{i}-{tag}'})
                    wait_batch(bulk_batch['id'],bulk)
                if mode=='window2':
                    remaining=iter(enumerate(papers))
                    with ThreadPoolExecutor(2) as clients:
                        pending=set()
                        while True:
                            while len(pending)<2:
                                item=next(remaining,None)
                                if item is None: break
                                i,p=item
                                pending.add(clients.submit(group,i,[p]))
                            if not pending: break
                            done,pending=wait(pending,return_when=FIRST_COMPLETED)
                            for completed in done: completed.result()
                else:
                    groups = [[p] for p in papers] if mode=='window1' else [list(papers)]
                    for i,ids in enumerate(groups): group(i,ids)
                return round(1000*(monotonic()-start_bulk))
            bulk_done = monitor.submit(run_bulk)
            provider.wait_for_active(1 if mode=='window1' else 2)
        start=monotonic()
        accepted=submit({'project_id':short,'document_ids':[doc],'request_id':f'urgent-{name}-{tag}'})
        received=monotonic()
        def visible():
            rows=specs(short,version)
            if rows:
                check(len(rows)==1 and rows[0]['document_id']==doc and rows[0]['business_version']==version
                      and version in rows[0]['body'], 'Wrong urgent evidence')
                return True
        until(visible,40,'Urgent specification not queryable')
        found=monotonic()
        wait_batch(accepted['id'],short)
        audit([doc],1)
        # At attempt 1 deadline_at = claim time + the fixed 120 s harness budget.
        # Both this time and the created_at timestamp come from the same Java host.
        waited=int(sql(f"SELECT deadline_at-120000-ROUND(EXTRACT(EPOCH FROM created_at::timestamptz)*1000)::bigint "
                       f"FROM {schema}.mb_tasks WHERE target_id='{UUID(doc)}' AND attempt=1 AND generation=0"))
        check(waited>=0,'Invalid claim timing')
        bulk_ms = bulk_done.result(timeout=45) if loaded else None
        if loaded:
            audit(papers,PAGES)
            for paper,rev in papers.items():
                rows=specs(bulk,rev)
                check(len(rows)==PAGES and all(row['document_id']==paper and row['business_version']==rev
                      and f'PAGE-{i:02d}' in row['body'] and row['location'].startswith(f'第{i}页字符')
                      for i,row in enumerate(rows,1)), 'PDF evidence mismatch: '+rev)
        return {'trial':name,'mode':mode,'submit_http_ms':round(1000*(received-start)),
                'task_created_to_claim_ms':waited,'submit_to_observed_queryable_ms':round(1000*(found-start)),
                'bulk_first_submit_to_last_observed_terminal_ms':bulk_ms,
                'pdf_bytes':pdf_bytes,'verified_pdf_pages':len(papers)*PAGES}

    result['trials']=[]
    modes = (('busy','window2') if experiment=='baseline' else ('busy',)) if experiment else ('idle','busy','window1')
    result['workload']['project_dispatch_window']=2 if experiment=='window2' else 0
    result['workload']['modes']=list(modes)
    with ThreadPoolExecutor(1) as monitor:
        measure('warmup','idle',monitor)
        for repeat in range(3):
            offset=repeat%len(modes)
            for mode in modes[offset:]+modes[:offset]:
                trial=measure(f'{repeat+1}-{mode}',mode,monitor)
                result['trials'].append(trial)
                print(trial,flush=True)
    result['median_ms']={}
    for mode in modes:
        samples=[r for r in result['trials'] if r['mode']==mode]
        keys=['submit_http_ms','task_created_to_claim_ms','submit_to_observed_queryable_ms']
        if mode!='idle':
            keys.append('bulk_first_submit_to_last_observed_terminal_ms')
        result['median_ms'][mode]={key:median(row[key] for row in samples) for key in keys}

    docs=[upload(short,f'replay-{n}') for n in (1,2)]
    key='response-lost-'+tag
    command={'project_id':short,'document_ids':docs,'request_id':key}
    first=dropped_response(submit,command)
    replay=submit({**command,'document_ids':list(reversed(docs))})
    check(first['id']==replay['id'],'Replaying accepted command created another batch')
    try:
        submit({**command,'document_ids':docs[:1]})
    except HTTPError as exc:
        check(exc.code==409,'Payload change did not return 409')
        exc.close()
    else:
        raise AssertionError('Different payload was accepted for the same request ID')
    final=wait_batch(replay['id'],short)
    check({i['document_id'] for i in final['items']}==set(docs) and final['total']==2,'Batch items changed')
    audit(docs,1)
    count=int(sql(f"SELECT count(*) FROM {schema}.mb_ingest_batches WHERE project_id='demo-project' AND request_key='{key}'"))
    check(count==1,'Expected one committed batch for request key')
    result['lost_response']={'upstream_http':202,'client':'RemoteDisconnected','replay_http':202,'conflict_http':409,
                             'same_batch_id':replay['id'],'database_batches':count,'file_tasks':2,'attempts_per_file':1,
                             'evidence_rows':2,'vector_rows':2}
    if experiment:
        version='eof-'+tag
        doc=upload(short,version)
        with provider.cv: provider.drop_marker=version
        accepted=submit({'project_id':short,'document_ids':[doc],'request_id':version})
        batch=wait_batch(accepted['id'],short)
        audit([doc],1,attempts=2)
        check(provider.drop_calls==2,'Expected exactly one closed model call and one successful retry')
        rows=specs(short,version)
        check(len(rows)==1 and rows[0]['document_id']==doc and version in rows[0]['body'],'Wrong EOF recovery result')
        result['injected_eof']={'task_id':batch['items'][0]['task_id'],'model_calls':2,'attempts':2,
                                'evidence_rows':1,'vector_rows':1,'outbox':'done'}
