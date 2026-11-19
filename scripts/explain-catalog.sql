-- SYNTHETIC ONLY. A temporary table and rollback leave business rows untouched.
-- Run against the isolated test PostgreSQL with psql; timings depend on this machine.
\set ON_ERROR_STOP on
BEGIN;
CREATE TEMP TABLE catalog_plan_check (LIKE mb_documents INCLUDING DEFAULTS) ON COMMIT DROP;
INSERT INTO catalog_plan_check
SELECT 'doc-'||n,'project-'||(n%10),'SYNTHETIC-'||n,'hash-'||n,
       'R'||(n%7), CASE WHEN n%3=0 THEN 'spec' ELSE 'design' END,
       'SYN-'||(n%100),'internal','synthetic-object','indexed', lpad(n::text,10,'0')
FROM generate_series(1,50000) n;
ANALYZE catalog_plan_check;
\echo BEFORE: project pagination, no supporting index
EXPLAIN (ANALYZE, BUFFERS)
SELECT id,name,business_version,status FROM catalog_plan_check
WHERE project_id='project-7' ORDER BY created_at DESC,id DESC LIMIT 20;
\echo BEFORE: exact specification, no supporting index
EXPLAIN (ANALYZE, BUFFERS)
SELECT id,name,business_version FROM catalog_plan_check
WHERE project_id='project-7' AND doc_type='spec'
  AND material_model='SYN-27' AND business_version='R2' AND status='indexed';
CREATE INDEX check_page ON catalog_plan_check(project_id,created_at,id);
CREATE INDEX check_specs ON catalog_plan_check(project_id,doc_type,material_model,business_version,status);
ANALYZE catalog_plan_check;
\echo AFTER: project pagination
EXPLAIN (ANALYZE, BUFFERS)
SELECT id,name,business_version,status FROM catalog_plan_check
WHERE project_id='project-7' ORDER BY created_at DESC,id DESC LIMIT 20;
\echo AFTER: exact specification
EXPLAIN (ANALYZE, BUFFERS)
SELECT id,name,business_version FROM catalog_plan_check
WHERE project_id='project-7' AND doc_type='spec'
  AND material_model='SYN-27' AND business_version='R2' AND status='indexed';
ROLLBACK;
