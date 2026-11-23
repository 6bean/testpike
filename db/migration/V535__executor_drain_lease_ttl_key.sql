-- V535: executor drain lease lifetime key (AA wave T294a package 1).
-- The drain ownership lease (tp_resource_lease kind=executor) resolves its TTL
-- from this key first; missing/invalid falls back to the global
-- lease.lease-ttl-seconds key, then the built-in default (same tiering chain
-- as the device/explore stages, single point = ResourceLeaseServiceImpl).
-- A drain whose lease lapsed is settled by the N4 fallback sweep: a live
-- pending scale-in command re-anchors (self-heal) the token, an abandoned one
-- returns the node to the pool (drain_requested released).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('executor.drain-lease-ttl-seconds', '900', '执行器池', 'int', 0,
     '执行器排空租约有效期秒数；排空超过该时限时由定时回收兜底（命令仍在途则续期，已遗弃则节点回池）。可热更。')
ON CONFLICT (config_key) DO NOTHING;
