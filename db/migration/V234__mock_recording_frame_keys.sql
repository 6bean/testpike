-- Migration: recording frame plane seeds .
-- mock.hit.max-body-chars is the single body-size bound every mock payload
-- shares (hit ledger request/response facets and recording frame bodies alike
-- — one MockBodyTruncator gate, no forked semantics): oversized bodies land
-- capped with a size-honest truncation tail note so a payload deluge never
-- bloats the inspector tables. mock.recording.hold-ttl-seconds keeps the
-- per-box Redis frame hold alive: an abandoned open box self-expires instead
-- of leaking staged frames forever.
-- Idempotent via WHERE NOT EXISTS.
-- Usage: docker exec -i -e PGPASSWORD=... testpike-postgres psql -U ... -d testpike -f - < this file
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
SELECT 'mock.hit.max-body-chars', '8192', '接口仿真', 'number', 0,
       'Mock 流量体截断上限（字符数）：命中流水请求/响应体与录制帧体同一闸门超限保头部并追加原长度标注（K8 热更，禁硬编码）'
WHERE NOT EXISTS (SELECT 1 FROM tp_sys_config WHERE config_key = 'mock.hit.max-body-chars');

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
SELECT 'mock.recording.hold-ttl-seconds', '86400', '接口仿真', 'number', 0,
       '录制盒帧暂存窗（秒）：Redis 暂存键保活时长，开盒弃置不封时到期自清（K8 热更）'
WHERE NOT EXISTS (SELECT 1 FROM tp_sys_config WHERE config_key = 'mock.recording.hold-ttl-seconds');
