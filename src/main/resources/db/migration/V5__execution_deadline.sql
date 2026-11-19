-- Existing Rabbit attempts are conservatively bounded by their current lease.
ALTER TABLE mb_tasks ADD COLUMN deadline_at BIGINT NOT NULL DEFAULT 0;
UPDATE mb_tasks SET deadline_at=lease_until WHERE dispatch_mode='rabbit' AND status='running';
CREATE INDEX mb_tasks_deadline ON mb_tasks(dispatch_mode,status,deadline_at);
