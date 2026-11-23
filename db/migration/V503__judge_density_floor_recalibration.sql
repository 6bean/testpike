-- Migration: re-adjudicate the judge-domain recall-density floor from the third
-- calibration window , and settle the
-- scenario-domain gate wording with the measured row-shape evidence.
-- Terminal evidence, all through the real chain (result file booked 03:28:36):
--   judge: 2074 items over an estimated 65546 tokens = 31.64 per k-token,
-- finished inside its segment (escape=false). The previous calibration
--     measured 34.39 on the same golden input; two runs on identical input
--     differ by ~8%, so the lower bound is taken to avoid false rejections
--     of healthy question-gen: floor = 31.64 x 0.6 = 19.0 (was 20.6).
--   scenario: row-shape evidence booked (row_id 127, batch 9030277): one
--     preview row carries the whole scenario set (8 entries) for a 40000
--     character requirement sample, so counting rows per k-token yields 0.03
--     and is not comparable with the per-entry domains. Floor stays 0
--     (gate off) and the description now records this as a measured shape
--     finding, not a pending calibration.
--   spec: 200 items over 10162 estimated tokens = 19.68, matching the V500
--     calibration figure exactly -> 11.8 re-confirmed, no change (the leg
--     escaped its sub-window with document status still pending; the booked
--     item count is persisted output, accepted as re-confirmation only).
--   mock: 540 items over 15975 estimated tokens = 33.8 -> 20.3 unchanged.
-- Values stay inside 0~50.
-- Usage: docker exec -i testpike-postgres psql -U testpike -d testpike -f - < db/migration/V503__judge_density_floor_recalibration.sql
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.judge.recall-density-floor-per-ktoken', '19.0', '系统', 'string', 0, '评测集造题的每千 token 产出条数下限（默认 19.0，允许 0~50，填 0 表示关闭该校验）。分母为估算 token 数（按字符数×0.75 折算，非计费 token）；标定值来自需求文档样张造题终态实测 31.64 条/千 token×0.6 安全系数（同输入两次实测 34.39 与 31.64，波动约 8%，取低值防止误拒正常产出）。值超出 0~50 区间或不是数字时保存会被拒绝，配置缺失或非法时按默认值 19.0 执行。保存即生效，无需重启）'),
    ('ai.gen.scenario-recall-density-floor-per-ktoken', '0', '系统', 'string', 0, '场景枚举的每千 token 产出条数下限（当前 0=该校验不启用：实测一行样张（40000 字符需求段）产出一整组场景条目，按行数折算的密度与其它按条计数的功能不可比，暂不启用该校验。允许 0~50，值超出区间或不是数字时保存会被拒绝。保存即生效，无需重启）')
ON CONFLICT (config_key) DO UPDATE SET config_value = EXCLUDED.config_value,
    description = EXCLUDED.description;
