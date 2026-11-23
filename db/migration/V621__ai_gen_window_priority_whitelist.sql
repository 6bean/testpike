-- Verification-window feed priority channel: project whitelist.
-- The header-only declaration would let any caller buy queue priority, so the
-- server honors it only for whitelisted projects. Empty value (shipped default)
-- keeps the whole channel closed; add project ids as a comma-separated list
-- (e.g. "3,7") and the change takes effect immediately without a restart.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.gen.window-priority.project-whitelist', '', '系统', 'string', 0, '验证窗优先排队的项目白名单（项目ID逗号分隔，如 3,7；仅名单内项目提交的批量任务走优先队列，名单外项目声明后仍按常规队列排队；留空=全部项目按常规队列；修改即时生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;
