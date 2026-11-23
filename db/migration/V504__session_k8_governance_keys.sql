-- Migration: AV batch K8 governance keys for the login-state capture chain
-- . Five hot-update knobs seeded as configuration rows so no
-- consumer hardcodes a window; the reader side (SessionGovernanceKeys) falls
-- back to the factory defaults mirrored there when a value is blank or
-- illegal, and a non-positive retention window refuses the cleanup sweep
-- instead of purging everything.
-- Usage: docker exec -i testpike-postgres psql -U testpike -d <slot-db> -f - < db/migration/V504__session_k8_governance_keys.sql
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('session.capture-timeout-minutes', '10', '系统', 'string', 0, '采集会话窗口上限（分钟，默认 10，允许 1~240）。从发起采集到勾选确认入册的全部操作时长超过该值时，本次采集会话作废并提示重新发起。保存即生效，无需重启）'),
    ('session.validate-timeout-ms', '8000', '系统', 'string', 0, '执行机侧有效性试请求的超时时间（毫秒，默认 8000，允许 500~60000）。请求超时按核验未通过处理，状态灯标明原因。保存即生效，无需重启'),
    ('session.renew-lead-minutes', '15', '系统', 'string', 0, '到期提醒提前量（分钟，默认 15，允许 0~1440）。登录态剩余有效期小于该值时，状态灯从「有效」转为「即将到期」。保存即生效，无需重启'),
    ('session.retention-days', '30', '系统', 'string', 0, '失效条目保留天数（默认 30，允许 1~365）。状态为失效且超过该天数的登录态条目，每日 04:30 清理其账本行；对应密文仅在该凭据由采集产生且无其他条目引用时一并删除，手工登记的凭据行不受影响。填 0 或负数时清理不执行（全量保留）。保存即生效，无需重启'),
    ('session.headless-host-capture.enabled', '0', '系统', 'string', 0, '执行机采集窗开关（1=开启，0=关闭，默认关闭）。关闭时如需在执行机上采集登录态，页面会提示改用本机浏览器直连采集；开启前需执行机已部署图形采集环境。修改入口：系统设置 → 配置管理，参数名称 session.headless-host-capture.enabled')
ON CONFLICT (config_key) DO NOTHING;
