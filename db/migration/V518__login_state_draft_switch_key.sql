-- Migration: piece D — login-state registration draft switch (factory default ON, R32).
-- Gates exactly the dialog card that files the CHECKED subset of a server-parsed
-- DevTools panel screenshot through the shared LoginStateService.register ledger
-- chain. Off + triggered = honest guidance naming this key and its settings path;
-- existing drafted cards are unaffected by the switch (the landing door owns them).
-- Read side: ChatRunOrchestrator draft-kind switch table. Write side: core
-- ConfigServiceImpl (bool, no interval guard needed). Applied manually to the dev
-- DB (Flyway not enabled; db/migration is reference + replay source for schema-init,
-- regenerated via tools/ops/gen_schema_init.sh).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.chat.login-state-draft.enabled', 'true', '系统', 'bool', 0,
     'AI 对话中「截图登记登录态」提案卡的开关（默认开启）。关闭后对话里粘贴登录态截图并让 AI 登记时会提示该能力已关闭及本参数名称，改为开启即可恢复；登录态管理页的 CDP 采集向导不受本开关影响。')
ON CONFLICT (config_key) DO NOTHING;
