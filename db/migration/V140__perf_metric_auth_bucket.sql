ALTER TABLE tp_perf_metric ADD COLUMN IF NOT EXISTS auth_ms NUMERIC(12,3);
ALTER TABLE tp_perf_metric ADD COLUMN IF NOT EXISTS auth_count NUMERIC(12,3);

COMMENT ON COLUMN tp_perf_metric.auth_ms IS '该秒认证请求耗时均值（毫秒，可空=该秒无认证点禁0顶位；auth 点已整体移出业务三线不入 rps/p95/错误率）';
COMMENT ON COLUMN tp_perf_metric.auth_count IS '该秒认证请求数（可空=该秒无认证点；认证风暴单列展示防误读业务瓶颈，不进业务熔断线）';
