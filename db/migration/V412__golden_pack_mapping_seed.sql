-- Migration: golden-pack mapping (behavior kernel chain, replay-side registry).
-- The replay executor needs one place to answer "which fixed sample set does
-- this template domain replay against". A single JSON map keyed by template
-- domain keeps the registry hot-reloadable (K8) instead of scattering one
-- config key per domain; value form:
--   {"<domain>": {"setName": "<judge eval-set name>", "version": <int>}}
-- Factory default is an empty map: no golden pack exists in a fresh install
-- until an operator freezes one, and the replay refuses with a message that
-- names this key and the page to edit it on (closed-state guidance).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.behavior.golden-pack.mapping', '{}', '系统', 'json', 0, '黄金样本包登记表（JSON 对象）：键=模板域名，值形如 {"setName":"评测集名称","version":"版本号"}；模板新版本晋升全量前会在对应样本集上回放，对比新旧版本输出变化比例。登记或修改后立即生效，无需重启；某域名未登记时，回放会提示补登记该键')
ON CONFLICT (config_key) DO NOTHING;
