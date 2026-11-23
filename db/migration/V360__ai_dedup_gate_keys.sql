-- Migration: seed the duplicate-gate key group (ai.dedup.*) for requirement writes.
--
-- Why this file exists:
--   Every create-type draft must pass the three-tier duplicate gate before a
--   new row lands, and the operator must be able to retune the gate without a
--   redeploy: the master switch, the two similarity lines that split the high
--   (refuse) / medium (present candidates) / low (pass) bands, and how many
--   near neighbours a medium verdict shows. Readers parse these strings and
--   clamp them (DedupGate), falling back to these seeded defaults on missing
--   or garbage values; the switch ships on (out-of-the-box duplicate
--   protection, no configuration required).
-- Applied manually to the dev DB (Flyway is not enabled; db/migration is
-- reference), then db/schema-init.sql regenerated via tools/ops/gen_schema_init.sh.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.dedup.enabled', '1', '系统', 'string', 0, '新建需求重复检出开关（默认开启；在本页修改即时生效；关闭后新建不再做重复检出，直接按原流程创建）'),
    ('ai.dedup.l2-high-score', '0.85', '系统', 'string', 0, '疑似重复高相似界线（0.05~1；在本页修改即时生效；标题相似度达到该值按重复拦截并点名既有条目）'),
    ('ai.dedup.l2-mid-score', '0.60', '系统', 'string', 0, '疑似重复中相似界线（0.05~高界线之间；相似度达到该值出相近条目清单供确认；低于该值直接放行）'),
    ('ai.dedup.candidate-top-n', '5', '系统', 'int', 0, '疑似重复相近条目展示条数（默认 5；区间 1~20，在本页修改即时生效）')
ON CONFLICT (config_key) DO NOTHING;
