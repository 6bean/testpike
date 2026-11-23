-- Migration: cross-message image replay cap for the dialog.
-- Bounds how many earlier attached images one turn may re-read by walking back
-- through message anchors (original images are re-fetched by objectKey; the
-- ledger injects per reference, never a full resend). Read side falls back to
-- the default below when the key is absent; write side (core ConfigServiceImpl
-- via AiChatKeyRanges) enforces the 1~10 interval. Applied manually to the dev
-- DB (Flyway not enabled; db/migration is reference + replay source for
-- schema-init, regenerated via tools/ops/gen_schema_init.sh).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.chat.image-replay-cap', '3', '系统', 'int', 0, '单轮提问可回看的历史贴图张数上限（默认 3；可设 1~10，热更即生效；更早的图片不会重新注入本轮，需要时请重新贴图）')
ON CONFLICT (config_key) DO NOTHING;
