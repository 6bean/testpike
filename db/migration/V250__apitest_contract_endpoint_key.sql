-- First-class match key on the API request table (AH batch W1c, stage 1).
-- Rationale: contract badge / inventory joins / change-impact closure all need to
-- resolve "which requests sit on this endpoint" by equality on a stored key.
-- Before this migration the key was recomputed per row at query time — fine for a
-- badge, but a full-table re-scan per endpoint once the inventory page lists N
-- endpoints, and the raw-string match was blind to hand-written concrete paths
-- (GET /orders/20261011001 never equals the contract template GET /orders/{id}).
-- Semantics (single source = ContractMatchKeyRules, this SQL mirrors it for the
-- one-time backfill only; the write path always recomputes via the Java rules):
--   key = upper(method) + ' ' + path, with variable-shaped segments folded to
--   {var}: template {x}, pure numeric, UUID, long hex (>=16); query string and
--   trailing slash stripped as in OpenApiSpecParser.normalizePath.
-- New K8 key spec.path-normalize.rules: CSV of extra segment regexes folded to
-- {var} (hot-update, built-in conservative rules need no config).
-- Manual execution (Flyway not enabled):
--   docker exec -i testpike-postgres psql -U testpike -d testpike < db/migration/V250__apitest_contract_endpoint_key.sql
-- After landing the new key, rebuild the config snapshot (pit 21 defence):
--   python3 scripts/tmp_m8p4_build_snapshot.py

ALTER TABLE tp_apitest_request
    ADD COLUMN IF NOT EXISTS contract_endpoint_key VARCHAR(1100) NULL;

COMMENT ON COLUMN tp_apitest_request.contract_endpoint_key IS
    'Normalized contract match key (upper METHOD + path with numeric/UUID/long-hex/template segments folded to {var}); written by the save chain, indexed for inventory joins and change-impact closure';

UPDATE tp_apitest_request
   SET contract_endpoint_key = upper(trim(method)) || ' ' ||
       regexp_replace(
           regexp_replace(
               regexp_replace(
                   regexp_replace(
                       regexp_replace(
                           CASE WHEN split_part(url_path, '?', 1) = '' THEN '/'
                                WHEN split_part(url_path, '?', 1) LIKE '/%'
                                     THEN split_part(url_path, '?', 1)
                                ELSE '/' || split_part(url_path, '?', 1) END,
                           '/{2,}', '/'),
                       '\{[^}]*\}', '{var}', 'g'),
                   '/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?=/|$)', '/{var}', 'g'),
               '/[0-9]+(?=/|$)', '/{var}', 'g'),
           '/[0-9a-fA-F]{16,}(?=/|$)', '/{var}', 'g')
 WHERE contract_endpoint_key IS NULL;

CREATE INDEX IF NOT EXISTS ix_apitest_request_contract_key
    ON tp_apitest_request (contract_endpoint_key);

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('spec.path-normalize.rules', '', 'API测试', 'string', 0,
     '契约端点路径归一的扩展规则表（逗号分隔的正则；整段命中即折为 {var} 通配位；模板段/纯数字/UUID/长十六进制为内置规则，加正则即扩、改键即生效、无需重启）')
ON CONFLICT (config_key) DO NOTHING;
