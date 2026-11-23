-- Migration: pinned-case grounding knobs (AM batch).
-- Seeds the two hot-updatable caps that bound how much case context rides a
-- pinned @-mention chip into one dialog turn:
--   ai.chat.pinned-grounding.review-max  = newest review opinions per case chip
--   ai.chat.pinned-grounding.failure-max = newest failed/timed-out runs per case chip
-- Read side (ai-service RefObjectResolver) falls back to the defaults below
-- when a key is absent; write side (core ConfigServiceImpl) enforces the
-- 1~10 interval via AiChatKeyRanges. Applied manually to the dev DB (Flyway
-- not enabled; db/migration is reference + replay source for schema-init).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.chat.pinned-grounding.review-max', '3', '系统', 'int', 0, '钉住用例时随问题附带的最新评审意见条数（默认 3；可设 1~10；超出条数的更早意见不会出现在引用块里）'),
    ('ai.chat.pinned-grounding.failure-max', '2', '系统', 'int', 0, '钉住用例时随问题附带的最近失败/超时执行记录条数（默认 2；可设 1~10；超出条数的更早记录不会出现在引用块里）')
ON CONFLICT (config_key) DO NOTHING;
