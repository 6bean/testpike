-- Migration: question-generation job plane ledger + sync answer cap .
-- Why this file exists:
--   Whole-document grouped question generation over long documents exceeds the
--   provider call window when answered inline (measured: document 8491 ran three
--   consecutive 300s timeouts). The submit face now keeps the synchronous answer
--   only while the sealed group count stays inside judge.qa-gen.sync-group-max
--   (default 1 group = today's healthy single-group face byte-identical), and
--   accepts a runToken-backed background job over that cap, landing one ledger
--   row per run plus one process event per landed group.
--   Every job-row write carries the run_token predicate, so an old background
--   run can never overwrite the newest row of the same document (preemption
--   boundary, mirrors the extraction token guard).
CREATE TABLE IF NOT EXISTS tp_qa_gen_job (
    id                BIGSERIAL PRIMARY KEY,
    document_id       BIGINT      NOT NULL,
    run_token         VARCHAR(64) NOT NULL,
    project_id        BIGINT,
    model             VARCHAR(128),
    status            VARCHAR(16) NOT NULL,
    total_groups      INT         NOT NULL DEFAULT 0,
    done_groups       INT         NOT NULL DEFAULT 0,
    items_json        TEXT        NOT NULL DEFAULT '[]',
    prompt_tokens     INT         NOT NULL DEFAULT 0,
    completion_tokens INT         NOT NULL DEFAULT 0,
    error_msg         VARCHAR(512),
    created_at        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_qa_gen_job_run_token ON tp_qa_gen_job (run_token);
CREATE INDEX IF NOT EXISTS ix_qa_gen_job_document ON tp_qa_gen_job (document_id, id DESC);

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('judge.qa-gen.sync-group-max', '1', '系统', 'string', 0, '分组造题同步返回的组数上限（默认 1，允许 1~50）。组数不超过该值时，一次调用直接返回完整预览；超过时自动转为后台任务，可实时查看每组进度后再勾选入库。保存即生效，无需重启')
ON CONFLICT (config_key) DO NOTHING;
