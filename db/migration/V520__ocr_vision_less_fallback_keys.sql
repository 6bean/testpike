-- Migration: BF batch half-slice B1b — vision-less OCR fallback keys (factory ON, R32).
-- ai.ocr.fallback.enabled gates the degradation branch both image-reading application
-- points take when ai.vision.model is blank: the local OCR engine transcribes
-- text-dense screenshots inside provenance markers (never presented as image
-- understanding); line-sparse images are honestly refused, never force-interpreted.
-- ai.ocr.fallback-line-min is the density floor: write side interval 1~20 via
-- AiOcrKeyRanges (ConfigServiceImpl single source), read side falls back to 3
-- when absent. Applied manually to the dev DB (Flyway not enabled; db/migration
-- is reference + replay source for schema-init, regenerated via
-- tools/ops/gen_schema_init.sh).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.ocr.fallback.enabled', 'true', '系统', 'bool', 0,
     '未配置视觉模型时，贴图与失败截图改由本地 OCR 引擎提取文字的开关（默认开启）。关闭后这两处会提示视觉模型未配置并仅基于文字回答；OCR 通道只转写截图中的文字，不解读画面内容。'),
    ('ai.ocr.fallback-line-min', '3', '系统', 'int', 0,
     '截图文字提取生效的最低行数（默认 3；可设 1~20，热更即生效）。提取到的文字行数低于该值时视为信息不足，会如实说明行数并放弃解读，不做勉强转写。')
ON CONFLICT (config_key) DO NOTHING;
