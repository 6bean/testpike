-- Migration: grounding material-role tuning keys (behavior-kernel item 6).
-- Semantic: every retrieved material now carries a role verdict so the dialog
-- answer can weigh sources honestly:
--   ai.ground.strong-score      = the relevance bar that separates strong
--                                 supporting material from weak background
--                                 material; an unrelated value left of the
--                                 bar would let marginal hits pose as proof.
--   ai.ground.stale-window-days = how many days a knowledge document may stay
--                                 trusted after its last content change; past
--                                 that window it is presented as possibly
--                                 out-of-date so the answer warns instead of
--                                 quoting it silently as current truth.
-- Both values are read fresh on every turn (hot reload, no restart needed);
-- out-of-range or unparsable values fall back to the shipped defaults, and
-- the settings page rejects writes outside the ranges spelled out below.
-- Applied manually to the dev DB (Flyway is not enabled; db/migration is
-- reference), then db/schema-init.sql regenerated via tools/ops/gen_schema_init.sh.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.ground.strong-score', '0.35', '系统', 'string', 0, '判定材料是强依据还是弱参考的分数线（默认 0.35；区间 0~1，低于该值的命中只作背景参考，不会当依据引用；超出范围按默认 0.35 处理，修改即时生效）'),
    ('ai.ground.stale-window-days', '7', '系统', 'int', 0, '知识文档距最近一次内容更新超过多少天后按可能过期对待（默认 7 天；区间 1~365；过期材料引用时回答会提示先核对时效；超出范围按默认 7 处理，修改即时生效）')
ON CONFLICT (config_key) DO NOTHING;
