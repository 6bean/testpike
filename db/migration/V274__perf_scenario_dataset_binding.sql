-- Migration: bind a perf scenario to a CSV dataset (data pool) with a row-allocation strategy.
-- Semantics: tp_perf_scenario.dataset_id = the uploaded CSV ledger row whose header column names
--   form the {{VAR}} domain consumed by steps; NULL = scenario runs without a data pool (today's
--   behaviour, zero regression). row_alloc_mode stores the scenario-level choice over the three
--   allocation strategies; NULL = resolve the K8 default key at dispatch time (hot-updatable,
--   never hardcoded at call sites).
-- Allocation strategies (value domain enforced at save, single source ROW_ALLOC_MODES in core):
--   SEQUENTIAL_EXCLUSIVE = each row is handed out once in file order (rows are consumed, never
--     duplicated while the run still has unleased rows);
-- VUSER_EXCLUSIVE = every virtual user owns a private segment and never shares a row;
--   CYCLE                = rows are reused round-robin after the pool is exhausted (safe default:
--     a run can never stall on "no row left", dirty data may be re-submitted).
-- K8 key: perf.dataset.row-alloc-mode = pool-wide default when the scenario leaves the column NULL;
--   hot-updatable; invalid/missing falls back to CYCLE in PerfConfigReader (single point).
-- FK note: tp_perf_dataset row cannot be deleted while a scenario still references it — the
--   dataset delete service pre-checks references and refuses with a plain-worded reason.
-- Idempotent re-run: ADD COLUMN IF NOT EXISTS + ON CONFLICT DO NOTHING.
-- Usage: docker cp db/migration/V274__perf_scenario_dataset_binding.sql testpike-postgres:/tmp/ && docker exec -e PGPASSWORD testpike-postgres psql -U testpike -d testpike -f /tmp/V274__perf_scenario_dataset_binding.sql
ALTER TABLE tp_perf_scenario ADD COLUMN IF NOT EXISTS dataset_id BIGINT REFERENCES tp_perf_dataset (id);
ALTER TABLE tp_perf_scenario ADD COLUMN IF NOT EXISTS row_alloc_mode VARCHAR(24);

COMMENT ON COLUMN tp_perf_scenario.dataset_id IS 'Bound CSV dataset (tp_perf_dataset.id); NULL = no data pool, steps resolve {{VAR}} only from non-dataset sources';
COMMENT ON COLUMN tp_perf_scenario.row_alloc_mode IS 'Row allocation strategy SEQUENTIAL_EXCLUSIVE/VUSER_EXCLUSIVE/CYCLE; NULL = follow K8 default key perf.dataset.row-alloc-mode at dispatch';

CREATE INDEX IF NOT EXISTS ix_perf_scenario_dataset ON tp_perf_scenario (dataset_id);

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('perf.dataset.row-alloc-mode', 'CYCLE', '系统', 'string', 0,
     '压测数据集行分配默认策略（场景未显式声明时生效：SEQUENTIAL_EXCLUSIVE 独占顺序/VUSER_EXCLUSIVE 每虚拟用户独占/CYCLE 循环复用；非法值回退 CYCLE，热更即生效）')
ON CONFLICT (config_key) DO NOTHING;
