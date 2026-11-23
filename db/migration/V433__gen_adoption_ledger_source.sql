-- Migration: adoption form ledger wiring for the shared outcome table ( piece 1,
-- domain consumes the ledger instead of building tp_ai_gen_outcome).
-- Changes:
--   1. ck_behavior_outcome_source gains the 'adoption' tag: rows written by the
--      derived adoption channel (human handling of an AI-produced case classified
--      from the before/after text pair — straight adoption / light edit / heavy
--      rewrite / deletion) ride the same dedup key (source, source_event_id).
--   2. K8 seed ai.gen.adoption-softedit-ratio = 0.2: normalized edit distance up
--      to which a touched case still counts as a light edit (partial); read fresh
--      on every derivation, out-of-range values fall back to this default.
-- Applied manually to this chain's slot DB (Flyway is not enabled; db/migration is
-- reference), then db/schema-init.sql regenerated via tools/ops/gen_schema_init.sh.

ALTER TABLE tp_ai_behavior_outcome
    DROP CONSTRAINT ck_behavior_outcome_source;

ALTER TABLE tp_ai_behavior_outcome
    ADD CONSTRAINT ck_behavior_outcome_source
    CHECK (source IN ('review', 'chip', 'draft', 'adoption'));

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.gen.adoption-softedit-ratio', '0.2', '系统', 'float', 0,
     'AI 生成的用例被人工修改后，改动比例不超过该值时记为轻改（默认 0.2；区间 0~1 不含端点；超出范围时按默认值生效；仅用于采纳情况统计，不拦截任何编辑；修改即时生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;

COMMENT ON CONSTRAINT ck_behavior_outcome_source ON tp_ai_behavior_outcome IS
    'Decision channel that produced the row: review, verification chip, draft confirmation, or the derived adoption form (T543).';
