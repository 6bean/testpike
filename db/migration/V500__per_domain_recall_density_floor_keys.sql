-- Migration: calibrated recall-density floor for the spec-contract domain
-- . The calibration window booked one proportional
-- sample per enumeration-recall domain through the real chain; the
-- spec-contract domain reached a terminal item count (200 items over an
-- estimated 10162 tokens = 19.68 items per k-token) and its floor ships as
-- measured density x 0.6 safety factor = 11.8, never extrapolated from the
-- doc-domain anchor. The three domains whose runs failed to reach a terminal
-- state (judge question-gen, scenario enumeration, mock-draft) ship 0 — the
-- check-off value, stated as "calibration pending" in each description; no
-- fabricated density. Each domain owns its own key prefix — values are never
-- borrowed across domains (reuse-contract A3 rule).
-- Denominator honesty: only the doc extraction path owns a server-side token
-- count, so this floor is per ESTIMATED tokens (char_count x 0.75, stated in
-- the user-facing description) and a later re-calibration with ledger
-- denominators may refine the value. Each domain owns its own key prefix —
-- values are never borrowed across domains (reuse-contract A3 rule).
-- Usage: docker exec -i testpike-postgres psql -U testpike -d testpike -f - < db/migration/V500__per_domain_recall_density_floor_keys.sql
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.spec.recall-density-floor-per-ktoken', '11.8', '系统', 'string', 0, '接口契约提取的每千 token 产出条数下限（默认 11.8，允许 0~50，填 0 表示关闭该校验）。分母为估算 token 数（按字符数×0.75 折算，非计费 token）；标定值来自等比例接口需求样张实测 19.68 条/千 token×0.6 安全系数。值超出 0~50 区间或不是数字时保存会被拒绝，配置缺失或非法时按默认值 11.8 执行。保存即生效，无需重启）'),
    ('ai.judge.recall-density-floor-per-ktoken', '0', '系统', 'string', 0, '评测集造题的每千 token 产出条数下限（当前 0=该校验不启用：本域样张标定尚未完成，禁按其它域数值外推；标定完成后填入实测密度×0.6 并改回。允许 0~50，值超出区间或不是数字时保存会被拒绝。保存即生效，无需重启）'),
    ('ai.gen.scenario-recall-density-floor-per-ktoken', '0', '系统', 'string', 0, '场景枚举的每千 token 产出条数下限（当前 0=该校验不启用：本域样张标定尚未完成，禁按其它域数值外推；标定完成后填入实测密度×0.6 并改回。允许 0~50，值超出区间或不是数字时保存会被拒绝。保存即生效，无需重启）'),
    ('ai.mock.recall-density-floor-per-ktoken', '0', '系统', 'string', 0, 'Mock 规则起草的每千 token 产出条数下限（当前 0=该校验不启用：本域样张标定尚未完成，禁按其它域数值外推；标定完成后填入实测密度×0.6 并改回。允许 0~50，值超出区间或不是数字时保存会被拒绝。保存即生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;
