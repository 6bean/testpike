
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('exec.console-assert.mode',      'fail',    '系统', 'string', 0, 'B11 console.assert 判定期望档（T192；fail=error 即败/warn=仅告警/off=关闭）'),
('exec.net-assert.enabled',       '1',       '系统', 'int',    0, 'B11 net.assert 总开关（T190b；0=命中记录仅展示不判败）'),
('exec.http.allow-hosts',         '',        '系统', 'string', 0, 'B11 http.request 主机白名单（逗号分隔，T189b 校验+T190b 执行双闸；空=回落项目环境域名集）'),
('exec.http.timeout-seconds',     '10',      '系统', 'int',    0, 'B11 http.request 单请求超时秒（T190b poll 首轮上限）'),
('sql.assert.timeout-seconds',    '30',      '系统', 'int',    0, 'B11 sql.assert 轮询总超时秒（T191a；默认轮询直至超时）'),
('sql.assert.interval-ms',        '2000',    '系统', 'int',    0, 'B11 sql.assert 轮询间隔毫秒（T191a 默认值，动作级 intervalMs 可覆盖）'),
('sql.max-rows',                  '1000',    '系统', 'int',    0, 'B11 sql.query/sql.exec 回传行数上限（T191b；截断+dbDigest 记数）'),
('ai.sql-gen.enabled',            '1',       '系统', 'int',    0, 'B11 AI 数据层动作生成开关（T193；0=stage2 不产 sql.*，灰度）')
ON CONFLICT (config_key) DO NOTHING;
