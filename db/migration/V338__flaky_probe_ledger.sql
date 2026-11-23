-- Probe ledger for flaky-quarantined cases plus the hot-updatable keys that drive the
-- re-probe loop .
--
-- Why this table exists:
--   Once a case is quarantined as flaky (marked either by the daily detection or by the
--   workbench one-click isolation, and now also proposed through the dialog draft card),
--   the loop has to answer "is it stable again?" without ever deciding that on its own.
--   One row per active probe session tracks the consecutive green runs observed since the
--   case was isolated, the execution-task cursor already consumed (so replaying a sweep
--   adds nothing), when the next probe may run, and whether a restore proposal is pending.
--   The partial unique index keeps at most one open session per case, which is what makes
--   opening a session idempotent across overlapping runs and multiple instances.
--
-- Direction of the door (unchanged from , and from the daily detection):
--   Reaching the green threshold only moves state to 1 (proposed) and sends one
--   notification. Clearing the quarantine itself stays a human action on the cases page
--   (CaseService.setFlaky with isolated=false); this table never clears tp_test_case.flaky.
--   A failure observed after a pending proposal moves the row back to 0 (probing), so a
--   stale suggestion cannot be confirmed against a case that proved unstable again.
--
-- State values: 0 = probing, 1 = restore proposal pending human confirmation, 2 = closed
--   (the case is no longer quarantined, so the session is finished and kept for history).
--
-- Idempotent via IF NOT EXISTS / ON CONFLICT DO NOTHING (re-run safe).
-- Usage: docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -f - < this file

CREATE TABLE IF NOT EXISTS tp_flaky_probe (
    id            BIGSERIAL    PRIMARY KEY,
    project_id    BIGINT       NOT NULL,
    case_id       BIGINT       NOT NULL,
    base_version  INT          NOT NULL,
    marked_at     TIMESTAMP    NOT NULL,
    green_streak  INT          NOT NULL DEFAULT 0,
    probed_runs   INT          NOT NULL DEFAULT 0,
    last_task_id  BIGINT       NOT NULL DEFAULT 0,
    state         SMALLINT     NOT NULL DEFAULT 0,
    next_probe_at TIMESTAMP    NOT NULL,
    proposed_at   TIMESTAMP    NULL,
    closed_at     TIMESTAMP    NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now()
);

COMMENT ON TABLE tp_flaky_probe IS
    'Re-probe ledger of quarantined (flaky) cases: consecutive green count, consumed execution cursor, next probe time and restore-proposal state (0 probing / 1 pending confirmation / 2 closed)';
COMMENT ON COLUMN tp_flaky_probe.base_version IS
    'tp_test_case.version when the session opened (audit anchor for the proposal card)';
COMMENT ON COLUMN tp_flaky_probe.green_streak IS
    'Consecutive green terminal results since the session opened; reset to 0 by any failure or timeout';
COMMENT ON COLUMN tp_flaky_probe.last_task_id IS
    'Highest tp_exec_task.id already folded into the streak (cursor: re-running a sweep re-reads nothing and writes nothing)';
COMMENT ON COLUMN tp_flaky_probe.next_probe_at IS
    'Earliest moment the next probe run may be dispatched, derived from ai.flaky.probe.interval-minutes';
COMMENT ON COLUMN tp_flaky_probe.proposed_at IS
    'When the restore proposal was raised (pending human confirmation; never applied automatically)';

-- At most one open session per case: opening a session twice in a row is a no-op.
CREATE UNIQUE INDEX IF NOT EXISTS uk_flaky_probe_open_per_case
    ON tp_flaky_probe (case_id) WHERE state IN (0, 1);
-- Sweep reads: due sessions first, and closing sessions by case lookup.
CREATE INDEX IF NOT EXISTS idx_flaky_probe_due
    ON tp_flaky_probe (state, next_probe_at);
CREATE INDEX IF NOT EXISTS idx_flaky_probe_case
    ON tp_flaky_probe (case_id);

-- Hot-updatable keys (no restart needed; the sweep reads them every round).
-- Default green threshold is 5, the same "five consecutive consistent results" bar the
-- daily detection already uses for a clear suggestion, so the loop never runs two bars.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('ai.flaky.probe.enabled', 'true', '系统', 'bool', 0,
     '不稳定用例回探开关（默认开启；被隔离的用例按「回探间隔」定期重跑，连续通过达到「转正建议所需连续通过次数」时提示可以解除隔离，需人工在用例库确认后才生效，系统不会自行解除；关闭时不再回探，也不提示解除，已隔离的用例保持隔离状态）'),
    ('ai.flaky.probe.interval-minutes', '30', '系统', 'int', 0,
     '回探间隔（分钟，默认 30；同一个被隔离用例两次重跑之间的最短间隔，数值越小越快收到解除提示，但更频繁占用执行资源）'),
    ('ai.flaky.probe.green-count', '5', '系统', 'int', 0,
     '转正建议所需连续通过次数（默认 5；被隔离用例连续通过达到该次数才提示可以解除隔离，中途出现失败或超时会重新计数）')
ON CONFLICT (config_key) DO NOTHING;
