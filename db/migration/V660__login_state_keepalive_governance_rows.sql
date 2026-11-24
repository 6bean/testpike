-- Migration: register governance verdicts for the three login-state
-- keepalive endpoints. An unregistered catalog entry is answered with the
-- generic "not registered" refusal, and the coverage gate requires every
-- endpoint to carry an explicit verdict in the seed set, because a fresh
-- install replays only schema-init/migrations (the live audit passes on a
-- database where the rows were registered out-of-band). No reachability
-- change: the two event reads are internal plumbing and the PATCH switch
-- stays a page-only mutation (write landing would additionally need the
-- write switch plus the confirmation card).

INSERT INTO tp_ai_api_access_governance
    (endpoint_key, scope_verified, reachable, ai_desc, exclude_reason)
VALUES
    ('GET /internal/session-states/{id}/keepalive-events', 0, 0, NULL,
     '该接口服务于系统内部联动与健康检查，不是业务查询面，无需在对话使用'),
    ('GET /internal/session-states/{id}/keepalive-runs', 0, 0, NULL,
     '该接口服务于系统内部联动与健康检查，不是业务查询面，无需在对话使用'),
    ('PATCH /internal/session-states/{id}/keepalive', 0, 0, NULL,
     '变更类操作暂未开放到对话，请在对应功能页面执行')
ON CONFLICT (endpoint_key) DO NOTHING;
