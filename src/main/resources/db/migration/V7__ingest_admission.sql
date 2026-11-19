CREATE TABLE mb_ingest_gate (id INTEGER PRIMARY KEY CHECK (id=1));
INSERT INTO mb_ingest_gate(id) VALUES(1);
CREATE INDEX mb_tasks_ingest_backlog ON mb_tasks(dispatch_mode,task_type,status,project_id);
