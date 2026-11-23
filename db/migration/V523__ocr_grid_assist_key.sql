-- Migration: BF batch supplement item 2 — grid-assist key (factory ON, R32).
-- ai.ocr.grid-assist.enabled manages exactly one branch: when a vision model IS
-- configured, the local OCR engine still transcribes value-shaped text (long ids,
-- cell values) of every attached screenshot as the deterministic initial baseline,
-- appended to the read round's observation block inside the <ocr_derived>
-- provenance markers. Value-shaped fields then take the OCR initial value as the
-- baseline and the vision description as semantic correction; per-round
-- disagreements between the two posts are counted, named in the block, and a WARN
-- line is logged — never silent. It is orthogonal to the ai.ocr.fallback.* family
-- (which only routes when ai.vision.model is blank): the two key families each
-- gate their own branch and must never be mixed. Bool write side accepts only
-- true/false/1/0 spellings (SysConfigBooleans.isLegal on the read side; absent
-- row = assist off, conservative posture). Applied manually to the dev DB (Flyway
-- not enabled; db/migration is reference + replay source for schema-init,
-- regenerated via tools/ops/gen_schema_init.sh).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.ocr.grid-assist.enabled', 'true', '系统', 'bool', 0,
     '已配置视觉模型时，是否仍让本地 OCR 引擎把截图中的长编号、单元格取值等逐字转写为初值基准（默认开启）。开启后贴图观察会附带一段 OCR 转写初值，编号类取值以 OCR 转写为准、画面理解由视觉模型校正，两者不一致时会在观察中点名；关闭后贴图仅按视觉模型描述回答。')
ON CONFLICT (config_key) DO NOTHING;
