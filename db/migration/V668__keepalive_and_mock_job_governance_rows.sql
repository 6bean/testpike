-- Migration: register governance verdicts for the three login-state keep-alive
-- endpoints that landed with the session ledger keep-alive batch. A fresh
-- install replays only schema-init/migrations, so a catalog entry without a
-- seed row answers with the generic "not registered" refusal and the
-- coverage contract goes red there even though live audits pass on a
-- database where the rows were registered out-of-band. No reachability
-- change: the two GETs serve the row-level process-detail replay of the
-- keep-alive tail window (internal plumbing for the ledger page), the PATCH
-- flips one ledger row's keep-alive switch — a change-class operation that
-- stays closed on the dialogue door and behind the human confirm gate.

INSERT INTO tp_ai_api_access_governance
    (endpoint_key, scope_verified, reachable, ai_desc, exclude_reason)
VALUES
    ('GET /internal/session-states/{id}/keepalive-events', 0, 0, NULL,
     '该接口服务于登录态账本页行级保活过程回放的读侧装配，不是业务查询面，无需在对话使用'),
    ('GET /internal/session-states/{id}/keepalive-runs', 0, 0, NULL,
     '该接口服务于登录态账本页行级保活轮次清单的读侧装配，不是业务查询面，无需在对话使用'),
    ('PATCH /internal/session-states/{id}/keepalive', 0, 0, NULL,
     '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('GET /internal/mock/jobs/{id}/process-events', 0, 0, NULL,
     '该接口服务于契约批量作业过程详情流的读侧装配，不是业务查询面，无需在对话使用')
ON CONFLICT (endpoint_key) DO NOTHING;
