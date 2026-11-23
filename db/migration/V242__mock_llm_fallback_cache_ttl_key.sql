-- AG batch (M10 : freshness horizon for the A-3 generated-answer cache.
--
-- Why this file exists:
-- The cache read side honors expires_at rows only while fresh, and
-- the write side stamps that horizon from a K8 hot key — which
--   must exist as a visible, tunable config row rather than a code-only
--   default. 72 hours mirrors the cassette-retention posture: long enough for
--   a burst of identical misses to be answered instantly, short enough that a
--   regenerated contract does not keep serving stale imagination for days.
--   <= 0 means "never expires by age" (V241 column semantics, sweep is the
--   reclaimer) — honest operators who want permanent casings can say so here.
--
-- Idempotent via ON CONFLICT DO NOTHING (re-run safe).
-- Usage: docker exec -i -e PGPASSWORD=... testpike-postgres psql -U ... -d testpike -f - < this file

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('mock.llm-fallback.cache-ttl-hours', '72', '接口仿真', 'int', 0,
     'A-3 兜底生成答案缓存新鲜期小时数：写入时按此钉 expires_at（<=0=不按年龄过期，靠清理消费者回收）；热更即时生效只影响新写入行，已有行的新鲜期不追溯改写')
ON CONFLICT (config_key) DO NOTHING;
