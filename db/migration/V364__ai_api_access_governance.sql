-- Migration: governance overlay and switches for the dialog pass-through door.
--
-- Why this table exists:
--   The catalog of callable endpoints is generated from the live request mappings, so nobody has
--   to register a tool row per capability any more. Generation can decide everything that is
--   structurally knowable (HTTP method, path shape, declared parameters, whether the endpoint
--   carries a project anchor). It cannot decide two things, and those two are the whole content of
--   this table:
--     1) an endpoint that carries no project anchor may still be safe to expose — that is a human
--        judgement, so it needs an explicit row with scope_verified = 1 to become reachable
--        (absent row = unreachable, fail-closed);
--     2) an endpoint that is cataloged but should not be served by the dialog (retired, or awaiting
--        a decision) is turned off here, with the reason kept next to it so a rejection reads as
--        a registered decision instead of a silent gap.
--   Red line carried from the tool surface: nothing in this table grants privilege. The scope guard
--   in the service still checks the acting operator against project membership on every anchored
--   call; this overlay only ever narrows the reachable set, never widens the identity rules.
--
-- Flyway is not wired in this project: apply manually against the target database (DBA path in
-- production), then regenerate db/schema-init.sql with scripts/gen_schema_init.sh so a fresh
-- install carries the same shape.

CREATE TABLE IF NOT EXISTS tp_ai_api_access_governance (
    id BIGSERIAL PRIMARY KEY,
    endpoint_key VARCHAR(200) NOT NULL,
    scope_verified SMALLINT NOT NULL DEFAULT 0,
    reachable SMALLINT NOT NULL DEFAULT 1,
    ai_desc VARCHAR(500),
    exclude_reason VARCHAR(300),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_api_access_governance_key
    ON tp_ai_api_access_governance (endpoint_key);

COMMENT ON TABLE tp_ai_api_access_governance IS
    'Governance overlay of the dialog pass-through door: human verdicts the generated catalog cannot derive';
COMMENT ON COLUMN tp_ai_api_access_governance.endpoint_key IS
    'Catalog key, format "HTTP-METHOD /path/pattern" (generated catalog is the authority on existence)';
COMMENT ON COLUMN tp_ai_api_access_governance.scope_verified IS
    '1 = verified that this anchor-less endpoint does not leak across projects; 0 = unreachable through the door';
COMMENT ON COLUMN tp_ai_api_access_governance.reachable IS
    '0 = retired from the door while keeping the catalog entry (an audit-visible decision, not a deletion)';
COMMENT ON COLUMN tp_ai_api_access_governance.ai_desc IS
    'What this endpoint answers, written for the dialog reader; blank falls back to a path-derived label';
COMMENT ON COLUMN tp_ai_api_access_governance.exclude_reason IS
    'Why the endpoint is out of reach; shown on rejection instead of silence';

-- Switches and ceilings (K8 face: hot-updatable from the system configuration page, no rebuild).
-- Defaults are enabled: the door serves reads only, and every ceiling is enforced per call.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.api-access.enabled', '1', '系统', 'string', 0,
     '对话接口直通（只读）开关（默认开启；在本页修改即时生效；关闭后对话不再代读本项目接口）'),
    ('ai.api-access.max-calls-per-window', '12', '系统', 'int', 0,
     '同一调用者在一个窗口内可代读的接口次数（默认 12；超限当次直接拒绝并提示本参数）'),
    ('ai.api-access.window-seconds', '600', '系统', 'int', 0,
     '代读次数窗口的秒数（默认 600；在本页修改即时生效）'),
    ('ai.api-access.row-max', '100', '系统', 'int', 0,
     '单次代读保留的列表行数（默认 100；超出按截断交付并标记 truncated）'),
    ('ai.api-access.response-max-bytes', '40000', '系统', 'int', 0,
     '单次代读回包的字节上限（默认 40000；超出交付带标记的预览，请改用参数收窄）'),
    ('ai.api-access.timeout-ms', '8000', '系统', 'int', 0,
     '代读调用目标接口的超时毫秒（默认 8000；超时当次失败并回原因）'),
    ('ai.api-access.catalog-max-rows', '120', '系统', 'int', 0,
     '接口目录一次交付的条目数（默认 120；超出提示带 keyword 精确检索）')
ON CONFLICT (config_key) DO NOTHING;
