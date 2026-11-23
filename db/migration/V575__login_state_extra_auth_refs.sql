-- Migration: a login state may ride a combined-session
-- header list (e.g. Bearer token + X-API-KEY belonging to one session).
-- The column stores references only: {credKey, into, headerName,
-- valueTemplate}; values stay in tp_credential (single encrypted vault) and
-- a valueTemplate may only use the shared {{CRED:key}} prefix grammar, so
-- no plaintext ever lands on the ledger row. Default '[]' keeps every
-- existing row byte-identical in behaviour (zero drift), and the per-state
-- ref cap becomes a hot config key.
-- Applied manually to the dev DB (Flyway not enabled; db/migration is the
-- reference + replay source for schema-init, regenerated via
-- tools/ops/gen_schema_init.sh).
ALTER TABLE tp_login_state ADD COLUMN IF NOT EXISTS extra_auth_refs JSONB NOT NULL DEFAULT '[]'::jsonb;
COMMENT ON COLUMN tp_login_state.extra_auth_refs IS 'Combined-session header list riding alongside the primary kind header; each item references a vault credential by credKey (never stores the value); valueTemplate is an optional prefix template in {{CRED:key}} form; empty array = single-header login state';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('session.state.extra-auth-max-refs', '8', '系统', 'int', 0, '单个登录态可携带的附加会话头条数上限（默认 8，保存即生效；超出会被拒绝并提示当前上限，可在本页调高）')
ON CONFLICT (config_key) DO NOTHING;
