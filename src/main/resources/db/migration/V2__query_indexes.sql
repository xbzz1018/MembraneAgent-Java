-- Same query responsibilities as the Python version, isolated Java tables.
CREATE INDEX mb_documents_lookup ON mb_documents(project_id,material_model,business_version,doc_type,status);
CREATE INDEX mb_tasks_target ON mb_tasks(task_type,target_id);
CREATE INDEX mb_issues_review_status ON mb_issues(review_id,status);
CREATE INDEX mb_events_target ON mb_events(project_id,target_id,created_at);
