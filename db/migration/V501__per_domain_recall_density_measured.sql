-- Migration: ship the measured recall-density floors from the second calibration
-- window . Terminal evidence, all through the
-- real chain: mock-draft booked 540 items over an estimated 15975 tokens
-- (33.8 per k-token, finished inside its segment) -> floor 20.3; judge
-- question-gen reached a terminal server-side job (12/12 groups, 2254 items
-- over an estimated 65546 tokens = 34.39 per k-token) a few minutes after its
-- 1800s tool segment escaped, so the value comes from that same terminal job,
-- not from extrapolation -> floor 20.6. The spec-contract floor 11.8 was
-- re-confirmed unchanged by a fresh run (200 items over 10162 estimated
-- tokens = 19.68). Scenario enumeration booked zero preview rows (batch 126)
-- through its whole segment and still had none ~50min later, so its floor
-- stays 0 with the pending-calibration wording and an investigation ledger on
-- the enumeration surface; no fabricated density. Values stay inside 0~50.
-- Usage: docker exec -i testpike-postgres psql -U testpike -d testpike -f - < db/migration/V501__per_domain_recall_density_measured.sql
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.judge.recall-density-floor-per-ktoken', '20.6', '系统', 'string', 0, '评测集造题的每千 token 产出条数下限（默认 20.6，允许 0~50，填 0 表示关闭该校验）。分母为估算 token 数（按字符数×0.75 折算，非计费 token）；标定值来自需求文档样张 12 组造题终态实测 34.39 条/千 token×0.6 安全系数。值超出 0~50 区间或不是数字时保存会被拒绝，配置缺失或非法时按默认值 20.6 执行。保存即生效，无需重启）'),
    ('ai.mock.recall-density-floor-per-ktoken', '20.3', '系统', 'string', 0, 'Mock 规则起草的每千 token 产出条数下限（默认 20.3，允许 0~50，填 0 表示关闭该校验）。分母为估算 token 数（按字符数×0.75 折算，非计费 token）；标定值来自等比例起草样张实测 33.8 条/千 token×0.6 安全系数。值超出 0~50 区间或不是数字时保存会被拒绝，配置缺失或非法时按默认值 20.3 执行。保存即生效，无需重启）')
ON CONFLICT (config_key) DO UPDATE SET config_value = EXCLUDED.config_value,
    description = EXCLUDED.description;
