-- Additive migration; legacy tasks are not automatically adopted by RabbitMQ.
ALTER TABLE mb_tasks ADD COLUMN dispatch_mode VARCHAR(16) NOT NULL DEFAULT 'legacy';
ALTER TABLE mb_tasks ADD COLUMN generation INT NOT NULL DEFAULT 0;
ALTER TABLE mb_tasks ADD COLUMN lease_until BIGINT NOT NULL DEFAULT 0;
ALTER TABLE mb_tasks ADD COLUMN last_error VARCHAR(512) NOT NULL DEFAULT '';
CREATE TABLE mb_task_outbox (
 task_id VARCHAR(64) PRIMARY KEY REFERENCES mb_tasks(id) ON DELETE CASCADE,
 generation INT NOT NULL, status VARCHAR(16) NOT NULL, available_at BIGINT NOT NULL,
 send_token VARCHAR(64), send_lease BIGINT NOT NULL DEFAULT 0, send_attempts INT NOT NULL DEFAULT 0,
 last_error VARCHAR(256) NOT NULL DEFAULT ''
);
CREATE INDEX mb_outbox_due ON mb_task_outbox(status,available_at,send_lease);
CREATE INDEX mb_tasks_recovery ON mb_tasks(dispatch_mode,status,lease_until);
CREATE TABLE mb_ingest_batches (
 id VARCHAR(64) PRIMARY KEY, project_id VARCHAR(64) NOT NULL REFERENCES mb_projects(id),
 request_key VARCHAR(128) NOT NULL, request_hash VARCHAR(64) NOT NULL,
 created_by VARCHAR(64) NOT NULL, created_at VARCHAR(40) NOT NULL,
 UNIQUE(project_id,request_key)
);
CREATE TABLE mb_ingest_batch_items (
 batch_id VARCHAR(64) NOT NULL REFERENCES mb_ingest_batches(id) ON DELETE CASCADE,
 document_id VARCHAR(64) NOT NULL REFERENCES mb_documents(id),
 task_id VARCHAR(64) NOT NULL REFERENCES mb_tasks(id) ON DELETE CASCADE,
 business_version VARCHAR(64) NOT NULL,
 PRIMARY KEY(batch_id,document_id)
);
CREATE INDEX mb_documents_page ON mb_documents(project_id,created_at,id);
CREATE INDEX mb_documents_specs ON mb_documents(project_id,doc_type,material_model,business_version,status);
