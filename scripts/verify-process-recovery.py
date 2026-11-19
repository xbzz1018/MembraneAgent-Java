"""Packaged-JAR crash/cancellation acceptance test; synthetic local infrastructure only.

Run after: JAVA_INFRA_TESTS=true mvn clean verify
Requires compose.test.yml services to be running. Starts/stops only its own Java
processes and local HTTP double. It never edits task state to simulate recovery.
"""
from __future__ import annotations

import hashlib
import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]
FLAGS = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
COMPOSE = ["docker", "compose", "-p", "membrane-java-check", "-f", "compose.test.yml"]


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def wait_until(check, seconds, label):
    until = time.monotonic() + seconds
    while time.monotonic() < until:
        value = check()
        if value:
            return value
        time.sleep(0.25)
    raise TimeoutError(label)


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


class ModelDouble(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self):
        super().__init__(("127.0.0.1", 0), ModelHandler)
        self.hold = threading.Event()
        self.entered = threading.Event()
        self.finished = threading.Event()
        self.calls = 0
        self.lock = threading.Lock()


class ModelHandler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *args):
        pass

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        if self.path != "/v1/embeddings":
            self.send_error(404)
            return
        with self.server.lock:
            self.server.calls += 1
        scenario = getattr(self.server, "mixed_model", None)
        if scenario is not None:
            status = scenario.respond(json.loads(body)["input"])
            if status == 'drop':
                self.close_connection = True
                return
            if status != 200:
                self.send_error(status)
                return
        self.server.entered.set()
        try:
            if not self.server.hold.wait(90):
                self.send_error(504)
                return
            payload = json.dumps({"data": [{"embedding": [1.0, 0.0, 0.0]}]}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass
        finally:
            self.server.finished.set()


def main(mixed=False, contention=False, experiment=None, peak=None):
    contention = contention or experiment is not None
    jar = ROOT / "target/membrane-business-backend-0.1.0-SNAPSHOT.jar"
    require(jar.is_file(), "Build the packaged jar first")
    tag = uuid4().hex[:12]
    kind = 'peak' if peak else ("contention" if contention else ("mixed" if mixed else "recovery"))
    schema = kind + "_" + tag
    artifacts = ROOT / "target" / (kind + "-" + tag)
    artifacts.mkdir(parents=True)
    model = ModelDouble()
    thread = threading.Thread(target=model.serve_forever, daemon=True)
    thread.start()
    port = free_port()
    base = f"http://127.0.0.1:{port}"
    process = None
    logs = []
    token = None
    result = {"synthetic": True, "schema": schema, "status": "running",
              "jar_sha256": hashlib.sha256(jar.read_bytes()).hexdigest()}
    command = [
        "java", "-jar", str(jar), "--spring.profiles.active=local-rabbit",
        f"--server.port={port}",
        f"--spring.datasource.url=jdbc:postgresql://127.0.0.1:55438/membrane_java?currentSchema={schema},public",
        f"--spring.flyway.schemas={schema}", f"--spring.flyway.default-schema={schema}",
        "--spring.flyway.locations=classpath:db/migration,classpath:db/postgres",
        "--spring.flyway.placeholders.embeddingDimensions=3",
        f"--app.jobs.queue=membrane.java.recovery.{tag}",
        f"--app.jobs.exchange=membrane.java.recovery.{tag}",
        "--app.jobs.lease-seconds=10", "--app.jobs.max-run-seconds=120",
        "--app.jobs.retry-seconds=1", "--app.jobs.concurrency=1", "--app.jobs.prefetch=1",
        "--app.http.request-timeout-seconds=60", "--app.model-mode=api",
        "--app.local-model=true", "--app.vector-enabled=true",
        "--app.embedding-model=synthetic-3d", "--app.embedding-dimensions=3",
        f"--app.embedding-base-url=http://127.0.0.1:{model.server_port}/v1",
        "--app.embedding-api-key=synthetic-local-double",
        "--app.query-adapter=", "--app.review-adapter=",
        "--app.jwt-secret=synthetic-process-recovery-secret-20261118-not-for-production",
        f"--app.storage={artifacts / 'objects'}",
    ]
    if mixed or contention or peak:
        command = [arg for arg in command if not arg.startswith(("--app.jobs.concurrency=", "--app.jobs.prefetch="))]
        command.extend(["--app.jobs.concurrency=2", "--app.jobs.prefetch=2", "--app.rate-capacity=3000"])
    if experiment:
        command.append('--app.jobs.project-dispatch-window='+('2' if experiment=='window2' else '0'))
        result['dispatch_experiment'] = experiment
    if peak:
        command.extend(['--app.jobs.project-dispatch-window=2',
                        '--app.jobs.project-backlog-limit='+('6' if peak.endswith('protected') else '0'),
                        '--app.jobs.total-backlog-limit='+('9' if peak.endswith('protected') else '0')])

    def request(path, payload=None, headers=None, raw=None):
        supplied = dict(headers or {})
        if token:
            supplied.setdefault("Authorization", "Bearer " + token)
        data = raw
        if payload is not None:
            data = json.dumps(payload).encode()
            supplied["Content-Type"] = "application/json"
        with urlopen(Request(base + path, data=data, headers=supplied), timeout=10) as response:
            if path == "/api/v1/ingest-batches":
                require(response.status == 202, "Batch was not accepted with HTTP 202")
            return json.loads(response.read())

    def start(number):
        nonlocal process, token
        log = (artifacts / f"app-{number}.log").open("wb")
        logs.append(log)
        process = subprocess.Popen(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
                                   creationflags=FLAGS)

        def ready():
            require(process.poll() is None, f"Java process exited; inspect {artifacts}")
            try:
                return request("/actuator/health").get("status") == "UP"
            except (URLError, TimeoutError):
                return False

        wait_until(ready, 70, "Application readiness")
        token = request("/api/v1/auth/login",
                        {"username": "admin", "password": "local-demo-change-me"})["access_token"]

    def psql(statement):
        completed = subprocess.run(
            COMPOSE + ["exec", "-T", "postgres", "psql", "-U", "java_check",
                       "-d", "membrane_java", "-t", "-A", "-v", "ON_ERROR_STOP=1",
                       "-c", statement], cwd=ROOT, capture_output=True, text=True,
            timeout=15, creationflags=FLAGS, check=True)
        return completed.stdout.strip()

    def sql(statement):
        require(statement.lstrip().upper().startswith("SELECT "), "Expected a diagnostic SELECT")
        return psql(statement)

    def upload(project, version):
        boundary = "membrane" + uuid4().hex
        parts = []
        fields = {"project_id": project, "version": version, "doc_type": "spec",
                  "material_model": "SYN-R100", "classification": "internal"}
        for name, value in fields.items():
            parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n')
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="synthetic.txt"\r\n'
                     f'Content-Type: text/plain\r\n\r\nSYNTHETIC SYN-R100 {version} thickness 1 mm\r\n--{boundary}--\r\n')
        return request("/api/v1/documents/upload",
                       headers={"Content-Type": "multipart/form-data; boundary=" + boundary},
                       raw="".join(parts).encode())["id"]

    def submit(project, document, name):
        return request("/api/v1/ingest-batches",
                       {"project_id": project, "document_ids": [document],
                        "request_id": f"{name}-{tag}"})

    def task_status(task):
        return request("/api/v1/tasks/" + task)

    def evidence_count(document):
        return int(sql(f"SELECT COUNT(*) FROM {schema}.mb_evidence WHERE document_id='{document}'"))

    try:
        start(1)
        if peak:
            from peak_load import run_peak
            run_peak(peak, model, request, upload, sql, schema, tag, result)
            result['status']='passed'
            print(json.dumps(result,indent=2),flush=True)
            return
        if contention:
            from contention_drill import run_contention
            model.hold.set()
            # Record committed failure transitions in this disposable schema. The probe
            # does not modify the task or seed a retry; a later claim clears last_error.
            psql(f"""CREATE TABLE {schema}.test_retry_trace(task_id text, attempt int, reason text);
                CREATE FUNCTION {schema}.test_trace_retry() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN INSERT INTO {schema}.test_retry_trace VALUES(NEW.id, NEW.attempt, NEW.last_error);
                RETURN NULL; END; $$;
                CREATE TRIGGER test_trace_retry AFTER UPDATE ON {schema}.mb_tasks FOR EACH ROW
                WHEN (OLD.status='running' AND NEW.status IN ('queued','failed'))
                EXECUTE FUNCTION {schema}.test_trace_retry()""")
            try:
                run_contention(model, request, upload, sql, schema, tag, result, experiment)
            finally:
                trace = sql(f"SELECT COALESCE(json_agg(r),'[]') FROM (SELECT task_id, attempt, reason "
                            f"FROM {schema}.test_retry_trace ORDER BY task_id,attempt) r")
                result['retry_transitions'] = json.loads(trace)
            result["status"] = "passed"
            print(json.dumps(result, ensure_ascii=False, indent=2), flush=True)
            return
        if mixed:
            from mixed_load import run_mixed
            model.hold.set()
            run_mixed(model, request, upload, sql, schema, tag, result)
            result["status"] = "passed"
            print(json.dumps(result, ensure_ascii=False, indent=2), flush=True)
            return
        project = request("/api/v1/projects", {"name": "SYNTHETIC process recovery " + tag})["id"]
        document = upload(project, "R1")
        batch = submit(project, document, "crash")
        task = batch["items"][0]["task_id"]
        require(model.entered.wait(15), "Worker did not enter the controlled downstream call")
        before = task_status(task)
        require(before["status"] == "running" and before["attempt"] == 1, "Expected first running attempt")
        require(evidence_count(document) == 0, "Results became visible before processing finished")
        old_pid = process.pid
        print(f"First attempt is running; force-stopping owned Java PID {old_pid}", flush=True)
        process.kill()
        process.wait(timeout=15)
        crash_time = time.monotonic()
        model.hold.set()
        start(2)

        def complete():
            current = task_status(task)
            require(current["status"] not in ("failed", "cancelled"), str(current))
            return current if current["status"] == "completed" else None

        after = wait_until(complete, 45, "Lease recovery after real process kill")
        require(after["attempt"] == 2, f"Expected recovered second attempt: {after}")
        require(evidence_count(document) == 1, "Expected exactly one committed evidence row")
        vectors = int(sql(f"SELECT COUNT(*) FROM {schema}.mb_vectors v JOIN {schema}.mb_evidence e"
                          f" ON v.evidence_id=e.id WHERE e.document_id='{document}'"))
        require(vectors == 1, "Expected exactly one vector")
        require(after["delivery"]["status"] == "done", "Outbox not finalized")
        result["crash_recovery"] = {"task_id": task, "old_pid": old_pid, "new_pid": process.pid,
                                    "attempt_before": 1, "attempt_after": after["attempt"],
                                    "evidence_rows": 1, "vector_rows": vectors,
                                    "seconds_including_restart": round(time.monotonic() - crash_time, 3),
                                    "task_state_edited_for_recovery": False}
        print("Process recovery passed; checking cancellation with an in-flight response", flush=True)

        model.hold.clear()
        model.entered.clear()
        model.finished.clear()
        cancelled_doc = upload(project, "R2")
        cancelled_batch = submit(project, cancelled_doc, "cancel")
        cancelled_task = cancelled_batch["items"][0]["task_id"]
        require(model.entered.wait(15), "Second task did not reach downstream")
        # Wait until broker statistics have observed this delivery. Otherwise an old
        # zero could make the later "drained" check pass before the worker actually acked.
        auth = __import__("base64").b64encode(b"java_check:synthetic-java-check-only").decode()
        queue_url = f"http://127.0.0.1:15638/api/queues/%2F/membrane.java.recovery.{tag}"

        def queue_state():
            with urlopen(Request(queue_url, headers={"Authorization": "Basic " + auth}), timeout=5) as response:
                return json.loads(response.read())

        wait_until(lambda: queue_state().get("messages_unacknowledged", 0) >= 1,
                   20, "Broker observing the in-flight delivery")
        request("/api/v1/tasks/" + cancelled_task + "/cancel", {})
        model.hold.set()
        require(model.finished.wait(15), "Controlled late response did not finish")

        def drained():
            queue = queue_state()
            return queue.get("messages", -1) == 0 and queue.get("messages_unacknowledged", -1) == 0

        wait_until(drained, 20, "Cancelled message acknowledgement")
        require(task_status(cancelled_task)["status"] == "cancelled", "Late response changed cancelled state")
        require(evidence_count(cancelled_doc) == 0, "Cancelled task published evidence")
        result["cancel_late_response"] = {"task_id": cancelled_task, "status": "cancelled",
                                          "evidence_rows": 0, "queue_drained": True}

        print("Cancellation passed; running packaged business smoke", flush=True)
        smoke = subprocess.run(
            ["pwsh", "-NoProfile", "-Command",
             f"& './scripts/smoke.ps1' -BaseUrl '{base}' | ConvertTo-Json -Depth 10"],
            cwd=ROOT, capture_output=True, encoding="utf-8", timeout=90,
            creationflags=FLAGS, check=True)
        result["business_smoke"] = json.loads(smoke.stdout)
        require(result["business_smoke"]["cache"] == "hit", "Expected real Redis cache hit")
        result["status"] = "passed"
        print(json.dumps(result, ensure_ascii=False, indent=2), flush=True)
    except Exception as exc:
        result["status"] = "failed"
        result["error"] = str(exc)
        raise
    finally:
        model.hold.set()
        if process is not None and process.poll() is None:
            process.kill()
            process.wait(timeout=15)
        model.shutdown()
        model.server_close()
        for log in logs:
            log.close()
        result["owned_java_processes_stopped"] = True
        (artifacts / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"Evidence: {artifacts / 'result.json'}", flush=True)


if __name__ == "__main__":
    args = argparse.ArgumentParser(description=__doc__)
    modes = args.add_mutually_exclusive_group()
    modes.add_argument("--mixed-load", action="store_true", help="Run the isolated mixed read/import and retry drill")
    modes.add_argument("--project-contention", action="store_true", help="Run PDF contention and lost-response replay drills (requires reportlab)")
    modes.add_argument("--dispatch-experiment",choices=("baseline","window2"),help="Compare unchanged single-batch submission with an optional server project window")
    modes.add_argument("--peak",choices=("baseline","protected","wave-baseline","wave-protected"),help="Run stalled or paced peak admission, concurrent reads and recovery")
    options = args.parse_args()
    main(options.mixed_load, options.project_contention, options.dispatch_experiment, options.peak)
