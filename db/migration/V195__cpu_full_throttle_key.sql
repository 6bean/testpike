
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
('appauto.device-cpu-full-pct', '95', 'APP自动化', 'int', 0, '移动端 CPU 满载阈值%（T329 热节流归因联动：失败/超时任务心跳摘要 CPU 峰值≥本值且温度越阈同现→执行任务 error_detail 追加设备热节流嫌疑注记，结果疑似热致假阳建议降温重跑；缺省 95，非法/缺键回退缺省，热更即生效）')
ON CONFLICT (config_key) DO NOTHING;
