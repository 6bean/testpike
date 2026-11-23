-- Failure-scene screenshot vision switch (AO-8 screenshot multimodal, knife 1).
--
-- Why this key exists:
--   When a badge-deep-link question carries a resolved failure diagnosis, the
--   dialog can additionally read the execution's last screenshot artifact
--   through the configured vision model and fold the observation into the
--   answer's evidence. This is the dialog-side application point of the vision
--   family: per the platform-wide rule that AI features ship switched ON so a
--   fresh install works out of the box, the sub-switch seeds to 1. It still
--   AND-gates with the shared master gate ai.vision.enabled (V155) — a closed
--   master gate produces one honest state frame naming both keys and where to
--   open them (system settings), never a silent half-material answer.
--
-- Value shape: int 0/1 (ai.vision.appauto-locate family precedent; the seed
-- lands in the same group the shared vision keys V155/V156 live in).
-- Idempotent via ON CONFLICT DO NOTHING (re-run safe).
-- Usage: docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -f - < this file

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('ai.vision.failure-screenshot', '1', '系统', 'int', 0,
     '对话失败现场截图读图开关：开启后从失败徽标进入的提问会附带该执行的末步截图，由视觉模型读出画面状态并入诊断依据；截图读取未成时回答仍基于失败详情文本并说明原因（需视觉模型总闸开启且已配置视觉模型）')
ON CONFLICT (config_key) DO NOTHING;
