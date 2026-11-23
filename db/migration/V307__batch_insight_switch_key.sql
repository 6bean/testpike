-- Batch failure-insight clustering switch (AO-7 proactive insights, stage 2b).
--
-- Why this key exists:
--   When a batch finalizes, the ai-service consumer clusters that batch's failed
--   tasks by root cause and lands an insight card (report page + station letter).
--   The clustering itself costs nothing (a read-out over rows already written by
--   the attribution chain), but the card is an AI feature — per the platform-wide
--   rule that AI features ship switched ON so a fresh install works out of the
--   box, the seed defaults to 1. Operators can close it; a closed gate still
--   leaves an audit row naming this key and where to turn it back on.
--
-- Value shape: int 0/1 (ai.classify.enabled family precedent), write-side range
-- check in ConfigService; read by the consumer through the core config endpoint.
-- Idempotent via ON CONFLICT DO NOTHING (re-run safe).
-- Usage: docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -f - < this file

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('ai.insight.enabled', '1', 'AI 配置', 'int', 0,
     '批次失败根因洞察开关：开启后批次执行完毕自动生成失败原因聚类卡并通知负责人；关闭后批次报告仅保留逐条失败明细，不再产出聚类卡')
ON CONFLICT (config_key) DO NOTHING;
