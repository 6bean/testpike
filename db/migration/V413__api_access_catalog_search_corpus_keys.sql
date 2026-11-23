-- Catalog corpus column + retrieval-shape keys for the pass-through door.
--
-- Context: the dialog reads the endpoint catalog on demand (search then read),
-- so prompt size never grows with registrations. What grows with the ledger is
-- search quality: an empty-keyword browse currently only ever shows the first
-- ceiling rows in lexicographic order, a zero-hit search returns a bare empty
-- list, and the only searchable text is the path plus a terse human label.
--
-- This migration lays the data-side groundwork that the follow-up packages
-- consume:
--   * search_terms -- a curated synonym column for the matcher (user-vocabulary
--     words from page/menu names; filled by the corpus package, never invented);
--   * catalog-group-max -- ceiling for the domain-group index view served when a
--     browse exceeds the row ceiling (the "index then drill-down" shape);
--   * catalog-recall-floor -- pass line for the offline recall ledger gate.
-- Columns and keys here ship ahead of their consumers: behavior changes only
-- when the matching/grouping/gate code lands; until then this is a no-op.
--
-- Flyway is not wired in this project: apply manually to the target database
-- (DBA path in production), then regenerate db/schema-init.sql with
-- tools/ops/gen_schema_init.sh.

ALTER TABLE tp_ai_api_access_governance
    ADD COLUMN IF NOT EXISTS search_terms VARCHAR(300);

COMMENT ON COLUMN tp_ai_api_access_governance.search_terms IS
    'Extra searchable synonyms for the catalog matcher; blank = none registered (path and label only)';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.api-access.catalog-group-max', '60', '系统', 'int', 0, '目录域索引形态一次交付的域行数上限（默认 60；超出部分折叠为一行并点名条数；修改即时生效）'),
    ('ai.api-access.catalog-recall-floor', '90', '系统', 'int', 0, '目录检索召回率闸门通过线（百分比，默认 90；回归问题集低于该值时质量闸标红并点名缺失条目；区间 10~100；修改即时生效）')
ON CONFLICT (config_key) DO NOTHING;
