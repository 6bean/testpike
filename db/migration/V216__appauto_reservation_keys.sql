
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('appauto.reservation.approval-required', '0', 'APP自动化', 'string', 0, '设备预约审批开关（T339；0=关直通创建即生效，1=创建落待审批走审批人批准/驳回，默认关，热更即生效）'),
('appauto.reservation.window-overrun-minutes', '0', 'APP自动化', 'int', 0, '设备预约窗超时自动释放宽限分钟（T339；ACTIVE 超窗≥宽限后清扫为已超时释放，待审批超窗未审无宽限直接释放，缺失/非法回退 0，热更即生效）')
ON CONFLICT (config_key) DO NOTHING;
