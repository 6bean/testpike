
ALTER TABLE tp_project_env ADD COLUMN IF NOT EXISTS cert_issuer varchar(200);
ALTER TABLE tp_project_env ADD COLUMN IF NOT EXISTS cert_not_after timestamp;
ALTER TABLE tp_project_env ADD COLUMN IF NOT EXISTS cert_chain_complete smallint;
ALTER TABLE tp_project_env ADD COLUMN IF NOT EXISTS cert_checked_at timestamp;

ALTER TABLE tp_exec_step_snapshot ADD COLUMN IF NOT EXISTS tls_mode varchar(16);
