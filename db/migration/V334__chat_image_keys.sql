-- Dialog user-image channel switches .
--
-- Why these keys exist:
--   Users can attach photos to a dialog question (phone-screenshot bug
--   reports). Two hot-updatable knobs govern the channel:
--   * ai.chat.image.max-attachments — per-question image cap. The run door
--     refuses an over-cap send naming both numbers (never a silent truncation),
--     so this value is the number quoted in that refusal.
--   * ai.vision.chat-user-image — whether the attached photos are read by the
--     configured vision model and folded into the answer's evidence. Per the
--     platform-wide rule that AI features ship switched ON (fresh install
--     works with zero setup) it seeds to 1, and still AND-gates with the
--     shared master gate ai.vision.enabled (V155): a closed master gate
--     produces one honest state frame naming both keys, never silence.
--
-- Value shape: both int (cap = positive count, switch = 0/1), hot-readable
-- via ConfigService with the code-side default as fallback.
-- Idempotent via ON CONFLICT DO NOTHING (re-run safe).
-- Usage: docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -f - < this file

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('ai.chat.image.max-attachments', '8', '系统', 'int', 0,
     '单条对话问题的图片附件上限（默认 4 张；超出时发送会被拒绝并提示本次张数与上限，改此值即时生效）'),
    ('ai.vision.chat-user-image', '1', '系统', 'int', 0,
     '对话用户图片读图开关：开启后随问题上传的图片会由视觉模型读出画面说明并入回答依据；读取未成时回答仍基于文字并说明原因（需视觉模型总闸开启且已配置视觉模型）')
ON CONFLICT (config_key) DO NOTHING;
