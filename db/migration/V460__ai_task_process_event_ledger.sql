-- Migration: process event ledger for long-running AI tasks (tail-follow face).
--
-- Why this file exists:
--   An 11-hour extraction run showed the operator only two coarse stage rows
--   (tp_doc_extract_stage, kept as-is) plus a 5-second full-page refresh hint —
--   the whole run was a black box. This table lands one append-only row per
--   FINE process step of any long AI job (requirement / case extraction, step
--   generation, batch execution, autonomous exploration, performance
--   attribution): chunk start, per-chunk model call with the visible reasoning
--   summary and token counts, parsed totals, retries, failure causes and
--   grouping milestones. Both the coarse stage ledger and this ledger are
--   written; the coarse face is never re-opened.
-- Behaviour notes:
--   * the BIGSERIAL id doubles as the stream cursor: strictly increasing, so a
--     reader replays the last cursor it saw and gets exactly the next window —
--     nothing skipped, nothing duplicated;
--   * rows are scoped by (job_kind, job_ref, run_token) the same way the
--     extraction generation token scopes its ledgers; the observation plane is
--     read-only and slices by the owning job's project — no widened access;
--   * window size, per-task cap and retention days are hot keys seeded below;
--   * writing stays advisory: a door outage must never fail the job itself.
CREATE TABLE IF NOT EXISTS tp_ai_task_event (
    id          BIGSERIAL    PRIMARY KEY,
    job_kind    VARCHAR(32)  NOT NULL,
    job_ref     VARCHAR(64)  NOT NULL,
    run_token   VARCHAR(64)  NOT NULL,
    level       VARCHAR(16)  NOT NULL,
    summary     VARCHAR(512) NOT NULL,
    detail      TEXT,
    created_at  TIMESTAMP    NOT NULL DEFAULT now()
);

COMMENT ON TABLE  tp_ai_task_event IS 'Process event stream of long-running AI jobs: one append-only row per fine step (chunk call, parsed totals, retry, failure cause, milestone) so the operator can watch the run live and intervene';
COMMENT ON COLUMN tp_ai_task_event.job_kind IS 'Registered job kind of the run (doc-extract / case-extract / steps-gen / batch-exec / explore / perf-attribution)';
COMMENT ON COLUMN tp_ai_task_event.job_ref IS 'Owning business object identity, e.g. document:8491 — the read side slices through the owner''s project';
COMMENT ON COLUMN tp_ai_task_event.run_token IS 'Generation token of one run: a re-triggered job starts a fresh event face, exactly like the extraction stage ledger';
COMMENT ON COLUMN tp_ai_task_event.level IS 'Viewer filter level, closed set: thinking (visible reasoning summary) / step (pipeline milestone) / error (failure cause)';
COMMENT ON COLUMN tp_ai_task_event.summary IS 'One-line human text of the step (visible reasoning note, milestone label or failure cause); the hidden chain-of-thought is never landed';
COMMENT ON COLUMN tp_ai_task_event.detail IS 'Optional JSON with the machine-readable part: token counts, parsed totals, retry cause';

CREATE INDEX IF NOT EXISTS ix_ai_task_event_stream
    ON tp_ai_task_event (job_kind, job_ref, run_token, id);

-- Hot-update keys (never hardcode these values in code):
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.stream.chunk-size', '200', '系统', 'int', 0, '过程详情每次增量读取的条数上限（默认 200，允许 10~1000）。数值越大单次返回越多。保存即生效，无需重启'),
    ('ai.stream.max-events-per-task', '2000', '系统', 'int', 0, '单个任务保留的过程事件条数上限（默认 2000，允许 100~20000）。超出后最早的记录自动清理。保存即生效，无需重启'),
    ('ai.stream.event-retention-days', '7', '系统', 'int', 0, '过程事件保留天数（默认 7，允许 1~90）。超期记录自动清理。保存即生效，无需重启')
ON CONFLICT (config_key) DO NOTHING;
