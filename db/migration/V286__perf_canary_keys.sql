-- Migration V286: canary pre-flight keys perf.canary.* .
-- Before this segment a launch committed the whole run contract before anything
-- ever touched the target: a dead or pathologically slow environment therefore
-- burned the approval window on a run that could only fail at press time. The
-- launch can now run an automatic low-load probe of the effective environment
-- first (reachability / TLS / baseline response time, plus a Gatling compile
-- pre-warm on the default engine); a failed probe rejects with zero residue,
-- and a passing outcome is pinned into the run snapshot for report attribution.
-- All four keys are hot-updatable (K8 discipline, no hardcoded thresholds):
--   perf.canary.enabled              master switch, default false keeps today's
--                                    launch behaviour byte-for-byte
--   perf.canary.requests             probe count per pre-flight (default 5)
--   perf.canary.probe-timeout-seconds per-request timeout (default 5)
--   perf.canary.baseline-p95-ms      measured p95 ceiling (default 3000) —
--                                    a target slower than this is refused
-- Flyway is not enabled: apply manually to the dev database.
-- Usage: docker cp db/migration/V286__perf_canary_keys.sql testpike-postgres:/tmp/ && docker exec -e PGPASSWORD testpike-postgres psql -U testpike -d testpike -f /tmp/V286__perf_canary_keys.sql

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('perf.canary.enabled', 'false', '系统', 'string', 0,
     '压测发起前金丝雀预检开关（默认关：发起行为与现状完全一致；开启后每次发起先对目标环境做低载探测，不可达或基线响应超阈时拒绝发起且不产生运行记录，通过后探测结果随运行快照留档供报告归因；热更即生效）'),
    ('perf.canary.requests', '5', '系统', 'int', 0,
     '金丝雀预检探测次数（默认 5，区间 1 以上，非法回退 5；每次预检对目标环境并发发出的低载请求条数；热更即生效）'),
    ('perf.canary.probe-timeout-seconds', '5', '系统', 'int', 0,
     '金丝雀预检单条请求超时秒数（默认 5，区间 1 以上，非法回退 5；超时未获响应的请求计入无响应数，全部无响应时拒绝发起；热更即生效）'),
    ('perf.canary.baseline-p95-ms', '3000', '系统', 'int', 0,
     '金丝雀预检基线响应时间上限毫秒（默认 3000，区间 1 以上，非法回退 3000；探测实测 p95 超过该值时拒绝发起并在提示中点名实测值与上限；热更即生效）')
ON CONFLICT (config_key) DO NOTHING;
