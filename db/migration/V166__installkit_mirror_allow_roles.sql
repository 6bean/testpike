
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('installkit.mirror-base-url', '', 'APP自动化', 'string', 0,
     '装机镜像源全局改道基址（留空=官方源；内网填公司 Nexus 基址，npm/pip 配方命令自动改道 <基址>/npm 与 <基址>/simple；改键即生效无需重启）'),
    ('installkit.allow-roles', 'admin,tester,test_lead,developer', 'APP自动化', 'string', 0,
     '允许签发装机入池令牌的操作人角色清单（逗号分隔；预置四角色=开箱全放，收紧示例改 admin；键缺失或留空=不限制）')
ON CONFLICT (config_key) DO NOTHING;
