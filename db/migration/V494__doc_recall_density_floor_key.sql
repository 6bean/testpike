-- Migration: hot budget key for the recall-density output floor on the
-- requirement-extraction reconcile plane .
--
-- Why this file exists:
--   Coverage books settle per cited block, so a dense source that every
--   citation settles on can still come back with a handful of items while
--   its real information density is far higher (live 8491/8639: 121k tokens
--   of source, a few dozen items, both ledger faces reading "done"). The
--   floor turns the run's own item count against the tokens actually shown
--   into a loop entry: fewer items than sourceTokens x floor / 1000 earns
--   targeted re-mining rounds under a dimension-focused ask.
-- Behaviour notes:
--   * the reader is fail-soft: a missing, blank, non-numeric or
--     out-of-window value answers the factory 6.4; only a legal number in
--     0~50 rides through, and 0 switches the check off entirely — this seed
--     only makes the knob visible and editable on the configuration page
--     (it is not a second default-value source);
--   * the write side validates the same 0~50 window so an illegal value
--     never reaches the hot read;
--   * a floor hit lands one line in the extraction log and one stage row on
--     the preview ledger, naming the received count, the floor and the
--     source tokens — the operator can see why the extra rounds burned.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.doc.recall-density-floor-per-ktoken', '6.4', '系统', 'string', 0, '每千 token 原文应产出的需求点条数下限（默认 6.4，允许 0~50，填 0 表示关闭该校验）。提取完成后条数低于「原文 token 数 × 该值 ÷ 1000」时，自动追加定向补充挖掘轮次，把没挖够的原文再挖一遍；补充轮仍无新增产出时提前结束，不会烧满轮次上限。值超出 0~50 区间或不是数字时保存会被拒绝，配置缺失或非法时按默认值 6.4 执行。保存即生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;
