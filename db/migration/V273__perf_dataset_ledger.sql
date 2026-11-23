-- Migration: perf CSV dataset ledger (data pool on MinIO with column preview and K8 row budget).
-- Semantics: one row = one uploaded CSV file of a project. The bytes live in object storage
--   (scene perf-dataset, project-routed like explore evidence); this table keeps only metadata:
--   validated header column names (columns_json, reused as {{VAR}} names in scenario steps),
--   the validated data-row count and the object key. Preview is streamed back from the object,
--   never from a duplicated DB copy of the payload.
-- K8 keys: perf.dataset.max-rows = upload row budget; perf.dataset.preview-rows = preview window.
--   Both hot-updatable; invalid/missing falls back to defaults in PerfConfigReader (single point).
-- Idempotent re-run: CREATE TABLE IF NOT EXISTS + ON CONFLICT DO NOTHING.
-- Usage: docker cp db/migration/V273__perf_dataset_ledger.sql testpike-postgres:/tmp/ && docker exec -e PGPASSWORD testpike-postgres psql -U testpike -d testpike -f /tmp/V273__perf_dataset_ledger.sql
CREATE TABLE IF NOT EXISTS tp_perf_dataset (
    id            BIGSERIAL    PRIMARY KEY,
    project_id    BIGINT       NOT NULL REFERENCES tp_project (id),
    name          VARCHAR(200) NOT NULL,
    file_name     VARCHAR(300) NOT NULL,
    object_key    VARCHAR(600) NOT NULL,
    columns_json  JSONB        NOT NULL,
    row_count     INT          NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_perf_dataset_row_count CHECK (row_count >= 1),
    CONSTRAINT uq_perf_dataset_name UNIQUE (project_id, name)
);

CREATE INDEX IF NOT EXISTS ix_perf_dataset_project ON tp_perf_dataset (project_id, id DESC);

COMMENT ON TABLE tp_perf_dataset IS 'Perf CSV dataset ledger: file bytes live in object storage (scene perf-dataset); this table keeps metadata only (validated columns, row count, object key)';
COMMENT ON COLUMN tp_perf_dataset.columns_json IS 'Validated CSV header names stored at upload; the same names form the {{VAR}} variable domain usable by scenario steps';
COMMENT ON COLUMN tp_perf_dataset.object_key IS 'Canonical object key (StorageKeys scene perf-dataset, project-routed; read and write resolve to the same provider)';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('perf.dataset.max-rows', '50000', '系统', 'int', 0,
     '压测数据集 CSV 行数上限（上传时按数据行计数校验，超限拒绝并提示当前上限；非法值回退 50000，热更即生效）'),
    ('perf.dataset.preview-rows', '20', '系统', 'int', 0,
     '压测数据集预览行数（查看数据集时回显的前 N 行数据；非法值回退 20，热更即生效）')
ON CONFLICT (config_key) DO NOTHING;
