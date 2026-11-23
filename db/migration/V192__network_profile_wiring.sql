INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES ('appauto.network-profiles',
  '{"2g":{"bw":50,"latency":700,"jitter":250,"loss":5},"3g":{"bw":1600,"latency":200,"jitter":60,"loss":1},"4g-weak":{"bw":6000,"latency":80,"jitter":30,"loss":0.5},"high-latency":{"bw":10000,"latency":1500,"jitter":200,"loss":0}}',
  'APP自动化', 'json', 0,
  '弱网档位库（L1 执行器节流代理预设档：2g/3g/4g-weak/high-latency，bw=带宽kbps、latency=延迟ms、jitter=抖动ms、loss=丢包%；热更生效=执行器下次档位翻译读取，执行器侧常量表为缺省兜底；自定义档走用例 custom 参数不经本键）')
ON CONFLICT (config_key) DO NOTHING;

UPDATE tp_appauto_engine SET capabilities = capabilities || '{
  "networkProfile": true, "network-throttle": true
}'::jsonb, updated_at = now()
WHERE code IN ('MOBILEWRIGHT','MAESTRO');
