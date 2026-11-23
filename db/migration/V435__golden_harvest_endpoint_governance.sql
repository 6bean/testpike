-- Migration: governance ledger row for the golden-pack collection endpoint
-- (, BA ruling item 8 — one-click harvest of adopted excellent AI cases
-- into a new immutable golden eval-set version consumed by the drift replay).
-- Coverage contract: every catalog entry carries an explicit verdict row. The
-- endpoint WRITES eval-set data (not a template status change), so it stays
-- closed to the dialog surface with the standard write-channel reason; the
-- harvest itself is already human-gated (hand-picked ids on the page).
-- Applied manually to this chain's slot DB (Flyway is not enabled; db/migration
-- is reference), then db/schema-init.sql regenerated via tools/ops/gen_schema_init.sh.

INSERT INTO tp_ai_api_access_governance
    (endpoint_key, scope_verified, reachable, ai_desc, exclude_reason)
VALUES
    ('POST /internal/judge/evalset/raise-from-adopted', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行')
ON CONFLICT (endpoint_key) DO NOTHING;
