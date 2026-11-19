CREATE TABLE mb_users (
 id VARCHAR(64) PRIMARY KEY, username VARCHAR(128) NOT NULL UNIQUE, password_hash VARCHAR(128) NOT NULL,
 tenant_id VARCHAR(64) NOT NULL, role VARCHAR(32) NOT NULL
);
CREATE TABLE mb_projects (id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, name VARCHAR(256) NOT NULL);
CREATE TABLE mb_members (project_id VARCHAR(64) NOT NULL REFERENCES mb_projects(id), user_id VARCHAR(64) NOT NULL REFERENCES mb_users(id),
 PRIMARY KEY(project_id,user_id));
CREATE TABLE mb_documents (
 id VARCHAR(64) PRIMARY KEY, project_id VARCHAR(64) NOT NULL REFERENCES mb_projects(id),
 name VARCHAR(256) NOT NULL, sha256 VARCHAR(64) NOT NULL, business_version VARCHAR(64) NOT NULL,
 doc_type VARCHAR(32) NOT NULL, material_model VARCHAR(128) NOT NULL, classification VARCHAR(16) NOT NULL,
 object_path VARCHAR(256) NOT NULL, status VARCHAR(32) NOT NULL, created_at VARCHAR(40) NOT NULL,
 UNIQUE(project_id,sha256,business_version)
);
CREATE TABLE mb_evidence (
 id VARCHAR(96) PRIMARY KEY, document_id VARCHAR(64) NOT NULL REFERENCES mb_documents(id),
 project_id VARCHAR(64) NOT NULL REFERENCES mb_projects(id), chunk_index INT NOT NULL,
 body TEXT NOT NULL, location VARCHAR(128) NOT NULL, text_sha256 VARCHAR(64) NOT NULL,
 UNIQUE(document_id,chunk_index)
);
CREATE INDEX mb_evidence_project ON mb_evidence(project_id,document_id);
CREATE TABLE mb_tasks (
 id VARCHAR(64) PRIMARY KEY, project_id VARCHAR(64) NOT NULL REFERENCES mb_projects(id),
 task_type VARCHAR(32) NOT NULL, target_id VARCHAR(64) NOT NULL, request_key VARCHAR(128) NOT NULL,
 request_hash VARCHAR(64) NOT NULL, status VARCHAR(32) NOT NULL, execution_id VARCHAR(256),
 attempt INT NOT NULL DEFAULT 0, claim_token VARCHAR(64), created_at VARCHAR(40) NOT NULL,
 UNIQUE(project_id,task_type,request_key)
);
CREATE TABLE mb_reviews (
 id VARCHAR(64) PRIMARY KEY, project_id VARCHAR(64) NOT NULL REFERENCES mb_projects(id),
 document_ids TEXT NOT NULL, status VARCHAR(32) NOT NULL, lock_version INT NOT NULL DEFAULT 0,
 content_hash VARCHAR(64), report TEXT NOT NULL, snapshot TEXT NOT NULL, created_by VARCHAR(64) NOT NULL
);
CREATE TABLE mb_issues (
 id VARCHAR(64) PRIMARY KEY, review_id VARCHAR(64) NOT NULL REFERENCES mb_reviews(id),
 summary TEXT NOT NULL, evidence_ids TEXT NOT NULL, status VARCHAR(32) NOT NULL,
 actual_value VARCHAR(128) NOT NULL, expected_value VARCHAR(128) NOT NULL
);
CREATE TABLE mb_commands (
 id VARCHAR(128) PRIMARY KEY, review_id VARCHAR(64) NOT NULL REFERENCES mb_reviews(id), actor VARCHAR(64) NOT NULL,
 request_hash VARCHAR(64) NOT NULL, decision VARCHAR(32) NOT NULL, reason TEXT NOT NULL, receipt TEXT NOT NULL
);
CREATE TABLE mb_events (
 id VARCHAR(64) PRIMARY KEY, project_id VARCHAR(64) NOT NULL, actor VARCHAR(64) NOT NULL,
 event_type VARCHAR(64) NOT NULL, target_id VARCHAR(64) NOT NULL, detail TEXT NOT NULL, created_at VARCHAR(40) NOT NULL
);
