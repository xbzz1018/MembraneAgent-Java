"""Synthetic workload used by verify-process-recovery.py --mixed-load (stdlib only)."""
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from math import ceil
from threading import Lock
from time import monotonic, sleep
from urllib.parse import urlencode
from uuid import UUID


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def p95(values):
    return round(sorted(values)[ceil(len(values) * .95) - 1], 2) if values else None


class ControlledModel:
    def __init__(self):
        self.lock = Lock()
        self.calls = Counter()
        self.slow_intervals = []

    def respond(self, text):
        version = text.split('SYN-R100 ', 1)[1].split()[0]
        with self.lock:
            self.calls[version] += 1
            attempt = self.calls[version]
        if version.startswith('slow-'):
            start = monotonic()
            sleep(.5)
            with self.lock:
                self.slow_intervals.append((start, monotonic()))
        if version == 'fault-auth':
            return 401
        if version == 'fault-busy' and attempt == 1:
            return 429
        return 200


def run_mixed(model, api, upload, sql, schema, tag, result):
    provider = ControlledModel()
    model.mixed_model = provider

    def project(label):
        return str(UUID(api('/api/v1/projects', {'name': f'SYNTHETIC {label} {tag}'})['id']))

    importer = project('import')
    # Demo employee belongs to demo-project; use it for reads, keep imports in a separate project.
    reader = 'demo-project'
    read_token = api('/api/v1/auth/login', {'username': 'employee', 'password':'local-demo-change-me'})['access_token']
    headers = {'Authorization': 'Bearer ' + read_token}

    def batch(project_id, docs, key):
        return api('/api/v1/ingest-batches', {'project_id':project_id, 'document_ids':docs, 'request_id':key+'-'+tag})

    def wait_batch(batch_id, timeout=40):
        deadline = monotonic()+timeout
        while monotonic() < deadline:
            row = api('/api/v1/ingest-batches/'+batch_id)
            if row['finished'] == row['total']:
                return row
            sleep(.1)
        raise TimeoutError('Batch did not reach terminal state: '+batch_id)

    seed = upload(reader, 'READ-R1')
    check(wait_batch(batch(reader,[seed],'seed-read')['id'])['status']=='completed', 'Seed not indexed')
    query = '/api/v1/specifications?' + urlencode(dict(project_id=reader, material_model='SYN-R100',version='READ-R1'))

    def query_once():
        start = monotonic()
        row = api(query, headers=headers)
        end = monotonic()
        check([(x['document_id'],x['business_version']) for x in row['results']]==[(seed,'READ-R1')],
              'Reader received wrong document/version')
        return start,end

    query_once()  # warm the same query for all three phases
    result['workload'] = {'consumer_concurrency':2, 'prefetch':2, 'files_per_batch':2, 'import_batches_per_phase':6,
                          'reader_requests_per_phase':48,'reader_interval_ms':100,'slow_embedding_ms':500,
                          'file_type':'synthetic single-chunk text','reader_cache':'warm','http_rate_capacity':3000,
                          'writer_account':'admin','reader_account':'employee'}
    result['phases'] = []

    def audit(project_id, docs):
        # Values come from generated document/project UUIDs; only read this run's schema.
        ids = ','.join("'"+str(UUID(d))+"'" for d in docs)
        rows = sql(f"SELECT t.target_id,t.attempt,t.status,o.status,count(e.id),count(v.evidence_id) "
                   f"FROM {schema}.mb_tasks t JOIN {schema}.mb_task_outbox o ON o.task_id=t.id "
                   f"LEFT JOIN {schema}.mb_evidence e ON e.document_id=t.target_id "
                   f"LEFT JOIN {schema}.mb_vectors v ON v.evidence_id=e.id "
                   f"WHERE t.project_id='{UUID(project_id)}' AND t.target_id IN ({ids}) "
                   "GROUP BY t.target_id,t.attempt,t.status,o.status")
        return {r[0]:(int(r[1]),r[2],r[3],int(r[4]),int(r[5])) for r in (line.split('|') for line in rows.splitlines())}

    for name in ('baseline','normal','slow'):
        docs = [upload(importer,f'{name}-{i:02d}') for i in range(12)] if name!='baseline' else []
        with ThreadPoolExecutor(4) as writers, ThreadPoolExecutor(4) as readers, ThreadPoolExecutor(1) as monitor:
            def submit_pair(i):
                before = monotonic()
                row = batch(importer,docs[i:i+2],name+str(i))
                accepted = monotonic()
                return row['id'], (accepted-before)*1000, accepted

            start = monotonic()
            pending = [writers.submit(submit_pair,i) for i in range(0,len(docs),2)]
            def observe_completion():
                submitted = [future.result() for future in pending]
                outstanding = {bid for bid,_,_ in submitted}
                deadline = monotonic()+40
                peak_queued = 0
                while outstanding:
                    check(monotonic()<deadline,'Did not drain accepted files')
                    queued = 0
                    for bid in tuple(outstanding):
                        view = api('/api/v1/ingest-batches/'+bid)
                        queued += view['counts']['queued']
                        if view['finished']==view['total']:
                            check(view['status']=='completed','Batch terminated with an error')
                            outstanding.remove(bid)
                    peak_queued = max(peak_queued,queued)
                    if outstanding:
                        sleep(.2)
                return submitted,monotonic(),peak_queued
            observer = monitor.submit(observe_completion)
            reads = []
            for i in range(48):
                sleep(max(0,start+i*.1-monotonic()))
                reads.append(readers.submit(query_once))
            samples = [f.result() for f in reads]
            submissions,done,peak_queued = observer.result()
        intervals = list(provider.slow_intervals)
        overlap = sum(any(a<y and b>x for x,y in intervals) for a,b in samples)
        if docs:
            expected = {d:(1,'completed','done',1,1) for d in docs}
            check(audit(importer,docs)==expected,'File-by-file DB reconciliation failed')
        if name=='slow':
            check(overlap>0,'No actual query overlapped a blocked downstream call')
        result['phases'].append({'phase':name,'queries_ok':len(samples),'queries_overlapping_slow_call':overlap,
                                 'query_p95_ms':p95([(b-a)*1000 for a,b in samples]),
                                 'accepted_batches':len(submissions),'submit_p95_ms':p95([ms for _,ms,_ in submissions]),
                                 'sampled_peak_queued_files':peak_queued,
                                 'observed_drain_after_last_202_s':round(done-max(s[2] for s in submissions),3) if docs else None,
                                 'completed_and_reconciled_files':len(docs)})
        print(f'{name}: {result["phases"][-1]}',flush=True)

    result['upstream_errors'] = {}
    for version,expected in [('fault-auth',(1,'failed','done',0,0)),('fault-busy',(2,'completed','done',1,1))]:
        doc = upload(importer,version)
        terminal = wait_batch(batch(importer,[doc],version)['id'])['items'][0]
        check(audit(importer,[doc])=={doc:expected} and provider.calls[version]==expected[0], 'Incorrect retry or result: '+version)
        if version=='fault-auth':
            check(terminal['last_error']=='model_credentials_rejected','Credentials failure was not diagnosable')
        result['upstream_errors'][version] = {'provider_calls':provider.calls[version], 'task_status':terminal['status'],
                                             'last_error':terminal['last_error'],'evidence_rows':expected[3]}
