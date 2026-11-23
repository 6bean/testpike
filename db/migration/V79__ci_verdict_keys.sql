INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
('ci.verdict.pass-rate', '0.95', '系统', 'string', 0, 'CI 门禁通过率阈值（V5/T228；默认 0.95，域 0~1 写侧服务层校验，计划 exit_criteria 可覆盖，热更即时生效；TC-V5-01/IT-V5-01）'),
('ci.verdict.p0-zero', '1', '系统', 'int', 0, 'CI 门禁 P0 零失败开关（V5/T228；1=开（P0 失败/超时逐条入未达项）0=关，仅 0 或 1 写侧校验；TC-V5-01/IT-V5-01）')
ON CONFLICT (config_key) DO NOTHING;
