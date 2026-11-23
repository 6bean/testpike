INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
  ('appauto.device-temp-max', '42', 'APP自动化', 'int', 0,
   '设备过热阈值℃（T329 热节流护栏：执行器采样摘要末帧/峰值温度越阈→设备过热注记+派发侧窗口内跳过+管理员告警；缺省 42，热更即生效）'),
  ('appauto.device-hot-window-minutes', '10', 'APP自动化', 'int', 0,
   '过热暂停派发窗口分钟（T329：末次越阈上报起算，窗口内矩阵扇出解析跳过该设备，过期自愈解禁；缺省 10，非法/缺键回退缺省）'),
  ('appauto.perf-sample-interval', '1', 'APP自动化', 'int', 0,
   '移动端性能采样间隔秒（T329 冻结 §14.2 频率 K8 键：派发信封 perfKeys 投影透传执行器，热更即生效；非法/缺键回退缺省 1s，执行器侧环境变量 EXECUTOR_PERF_SAMPLE_INTERVAL 为信封缺位兜底）')
ON CONFLICT (config_key) DO NOTHING;

ALTER TABLE tp_appauto_device ADD COLUMN IF NOT EXISTS last_overheat_at TIMESTAMP NULL;
COMMENT ON COLUMN tp_appauto_device.last_overheat_at IS '末次高温越阈上报时刻（热节流护栏注记单点：心跳摘要行温度越 appauto.device-temp-max 即刷新；派发侧按 appauto.device-hot-window-minutes 窗口内跳过，禁第六 health_state 态）';
