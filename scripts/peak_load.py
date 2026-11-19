"""Controlled burst, admission rollback, concurrent reads and recovery; synthetic local data only."""
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from threading import Event, Lock
from time import monotonic, sleep
from urllib.error import HTTPError
from urllib.parse import urlencode
from uuid import UUID
import json

from mixed_load import check, p95


def run_peak(mode, model, api, upload, sql, schema, tag, result):
    protected = mode == 'protected'
    path = '/api/v1/ingest-batches'
    hot = 'demo-project'
    projects = [hot] + [str(UUID(api('/api/v1/projects', {'name': 'SYNTHETIC peak '+i+tag})['id'])) for i in 'BC']
    reader = api('/api/v1/auth/login', {'username':'employee','password':'local-demo-change-me'})['access_token']
    headers = {'Authorization':'Bearer '+reader}
    def command(docs, project, label):
        return {'project_id':project,'document_ids':docs,'request_id':tag+'-'+label}

    def submit(cmd):
        start = monotonic()
        try:
            row = api(path,cmd)
            outcome = (202,row['id'])
        except HTTPError as exc:
            with exc:
                detail=json.load(exc)
                check((exc.code,detail.get('code')) in [(429,'project_ingest_backlog_full'),(503,'platform_ingest_backlog_full')]
                      and exc.headers.get('Retry-After')=='2','Unexpected rejection: '+str(detail))
                outcome = (exc.code,None)
        return cmd,*outcome,(monotonic()-start)*1000

    def finished(row):
        check(row['status']!='finished_with_errors','File processing failed')
        return row['status']=='completed'

    def wait_batches(ids):
        pending=set(ids); deadline=monotonic()+65
        while pending:
            pending={bid for bid in pending if not finished(api(path+'/'+bid))}
            check(monotonic()<deadline,'Timed out draining accepted batches')
            if pending: sleep(.1)

    model.hold.set()
    version='seed-'+tag
    seed=upload(hot,version)
    wait_batches([api(path,command([seed],hot,'seed'))['id']])
    query='/api/v1/specifications?'+urlencode(dict(project_id=hot,material_model='SYN-R100',version=version))
    def read_spec():
        rows=api(query,headers=headers)['results']
        check([(r['document_id'],r['business_version']) for r in rows]==[(seed,version)],'Wrong cached specification')
    read_spec()
    if mode.startswith('wave-'):
        run_wave(mode,model,api,upload,sql,schema,tag,projects,command,submit,wait_batches,read_spec,result)
        return
    commands=[[command([upload(project,'peak-'+tag+'-'+str(i))],project,project+'-'+str(i)) for i in range(n)]
              for project,n in zip(projects,(10,5,5))]
    model.entered.clear(); model.hold.clear()
    accepted0=submit(commands[0][0]); check(accepted0[1]==202,'Anchor refused')
    anchor=accepted0[2]
    check(model.entered.wait(12),'Anchor never reached model')

    def read_progress():
        row=api(path+'/'+anchor,headers=headers)
        check(row['items'][0]['document_id']==commands[0][0]['document_ids'][0]
              and row['finished'] in (0,1) and row['total']==1,'Wrong batch progress')
    done=Event()
    def poll(read):
        lat=[]
        while not done.is_set():
            start=monotonic(); read(); lat.append((monotonic()-start)*1000); done.wait(.1)
        return lat

    with ThreadPoolExecutor(4) as readers, ThreadPoolExecutor(8) as writers:
        reads=[readers.submit(poll,f) for f in (read_spec,read_spec,read_progress,read_progress)]
        try:
            outcomes=[accepted0]+list(writers.map(submit,commands[0][1:]))
            outcomes+=list(writers.map(submit,[c for pair in zip(commands[1],commands[2]) for c in pair]))
            counts=Counter(o[1] for o in outcomes)
            check(counts==(Counter({202:9,429:4,503:7}) if protected else Counter({202:20})),str(counts))
            check(submit(commands[0][0])[1:3]==(202,anchor),'Replay of accepted request failed at capacity')
            plateau=int(sql(f"SELECT COUNT(*) FROM {schema}.mb_tasks WHERE status IN ('running','queued')"))
            check(plateau==counts[202],'Uncommitted or excessive backlog')
            for table in ('mb_ingest_batches','mb_tasks','mb_task_outbox'):
                check(int(sql(f'SELECT COUNT(*) FROM {schema}.{table}'))==1+plateau,'Rejected work leaked into '+table)
            release=monotonic(); model.hold.set()
            wait_batches([o[2] for o in outcomes if o[1]==202]); drain=monotonic()-release
        finally:
            model.hold.set(); done.set()
        samples=[f.result() for f in reads]
    check(all(len(x)>=2 for x in samples),'Insufficient concurrent reads')
    rejected=[o[0] for o in outcomes if o[1]!=202]
    for start in range(0,len(rejected),3):
        retry=[submit(c) for c in rejected[start:start+3]]
        check(all(o[1]==202 for o in retry),'Original request key not reusable after drain')
        wait_batches([o[2] for o in retry])
    expected={c['document_ids'][0] for group in commands for c in group}|{seed}
    rows=sql(f"SELECT t.target_id,t.attempt,t.status,o.status,count(e.id),count(v.evidence_id) FROM {schema}.mb_tasks t "
             f"JOIN {schema}.mb_task_outbox o ON t.id=o.task_id LEFT JOIN {schema}.mb_evidence e ON e.document_id=t.target_id "
             f"LEFT JOIN {schema}.mb_vectors v ON v.evidence_id=e.id GROUP BY t.id,t.target_id,t.attempt,t.status,o.status")
    check(sorted(r.split('|') for r in rows.splitlines())==sorted([d,'1','completed','done','1','1'] for d in expected),
          'Final per-document reconciliation failed')
    check(int(sql(f'SELECT COUNT(*) FROM {schema}.mb_ingest_batches'))==len(expected),'Unexpected final batch count')
    result['peak']={'mode':mode,'files_per_project':[10,5,5],'writer_threads':8,'reader_threads':4,'consumers':2,'prefetch':2,
        'dispatch_window':2,'project_limit':6 if protected else 0,'global_limit':9 if protected else 0,
        'http_outcomes':dict(counts),'backlog_before_release':plateau,'drain_after_release_s':round(drain,3),
        'accepted_submit_p95_ms':p95([o[3] for o in outcomes if o[1]==202]),
        'rejected_submit_p95_ms':p95([o[3] for o in outcomes if o[1]!=202]),
        'read_samples':{'spec':len(samples[0]+samples[1]),'progress':len(samples[2]+samples[3])},
        'read_p95_ms':{'spec':p95(samples[0]+samples[1]),'progress':p95(samples[2]+samples[3])},
        'same_key_replayed_at_capacity':True,'rejected_later_accepted':len(rejected),'reconciled_files_excluding_seed':len(expected)-1}


def run_wave(mode,model,api,upload,sql,schema,tag,projects,command,submit,wait_batches,read_spec,result):
    phases=[('normal',4,2),('peak',36,12),('cooldown',4,2)]
    schedule=[]; offset=0
    for phase,count,rate in phases:
        for i in range(count):
            n=len(schedule); project=projects[n%len(projects)]
            cmd=command([upload(project,'wave-'+tag+'-'+str(n))],project,'wave-'+str(n))
            schedule.append((phase,offset+i/rate,cmd))
        offset+=count/rate
    stop=Event(); samples=[]; sql_samples=[]
    class SlowModel:
        def __init__(self):
            self.lock=Lock(); self.calls=0; self.active=0; self.max_active=0
        def respond(self,text):
            with self.lock:
                self.calls+=1; self.active+=1; self.max_active=max(self.max_active,self.active)
            try: sleep(.4)
            finally:
                with self.lock: self.active-=1
            return 200
    provider=SlowModel(); model.mixed_model=provider
    def reader():
        local=[]
        while not stop.is_set():
            a=monotonic(); read_spec(); local.append((monotonic()-a)*1000); stop.wait(.1)
        return local
    def monitor():
        while not stop.is_set():
            sql_samples.append(int(sql(f"SELECT COUNT(*) FROM {schema}.mb_tasks WHERE status IN ('queued','running')")))
            stop.wait(.15)
    with ThreadPoolExecutor(8) as writers, ThreadPoolExecutor(3) as observers:
        watching=observers.submit(monitor); reads=[observers.submit(reader) for _ in range(2)]
        try:
            start=monotonic(); futures=[]
            for phase,at,cmd in schedule:
                sleep(max(0,start+at-monotonic())); futures.append((phase,writers.submit(submit,cmd)))
            responses=[(phase,f.result()) for phase,f in futures]; last_response=monotonic()
            accepted=[r for _,r in responses if r[1]==202]; wait_batches([r[2] for r in accepted]); drain=monotonic()-last_response
        finally: stop.set()
        watching.result()
        for f in reads: samples+=f.result()
    expected={r[0]['document_ids'][0] for r in accepted}
    keys=[str(UUID(cmd['document_ids'][0])) for _,_,cmd in schedule]
    docs=','.join("'"+k+"'" for k in keys)
    rows=sql(f"SELECT t.target_id,t.attempt,t.status,o.status,count(e.id),count(v.evidence_id) FROM {schema}.mb_tasks t "
             f"JOIN {schema}.mb_task_outbox o ON o.task_id=t.id LEFT JOIN {schema}.mb_evidence e ON e.document_id=t.target_id "
             f"LEFT JOIN {schema}.mb_vectors v ON e.id=v.evidence_id WHERE t.target_id IN ({docs}) "
             "GROUP BY t.id,t.target_id,t.attempt,t.status,o.status")
    check(sorted(r.split('|') for r in rows.splitlines())==sorted([d,'1','completed','done','1','1'] for d in expected),'Wave reconciliation failed')
    check(int(sql(f"SELECT COUNT(*) FROM {schema}.mb_ingest_batches"))==1+len(expected),'Unexpected batch count')
    check(provider.calls==len(expected) and provider.max_active==2 and len(samples)>10,'Invalid downstream/read overlap')
    check(bool(sql_samples) and int(sql(f"SELECT COUNT(*) FROM {schema}.mb_tasks WHERE status IN ('queued','running')"))==0,'Did not drain')
    if mode.endswith('protected'):
        check(max(sql_samples)<=9 and 0<len(expected)<len(keys),'Protected run did not exercise admission')
    else: check(len(expected)==len(keys),'Baseline unexpectedly rejected work')
    result['wave']={'mode':mode,'offered_schedule':phases,'embedding_delay_ms':400,'dispatch_window':2,'active_consumers':2,
        'outcomes_by_phase':{name:dict(Counter(r[1] for phase,r in responses if phase==name)) for name,_,_ in phases},
        'submitted':len(keys),'accepted_and_reconciled':len(expected),'rejected_without_records':len(keys)-len(expected),
        'max_observed_outstanding':max(sql_samples),'backlog_samples':len(sql_samples),'final_outstanding':0,
        'max_model_calls_in_parallel':provider.max_active,'read_requests':len(samples),'spec_p95_ms':p95(samples),
        'drain_after_last_submission_response_s':round(drain,3)}
