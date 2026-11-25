-- Migration: register the two spec-upgrade bridge endpoints shipped after the
-- V657 full-ledger sweep. The runtime catalog (handler-mapping scan) carries them
-- while the governance table does not, so the coverage guard goes red. Rows keep
-- the door's live verdict unchanged: both stay closed with reader-facing reasons
-- (no reachability change; opening either needs its own named verification).
INSERT INTO tp_ai_api_access_governance
    (endpoint_key, scope_verified, reachable, ai_desc, exclude_reason)
VALUES
    ('GET /internal/mock/spec-upgrade/plan', 0, 0, NULL,
     '该查询的数据可见范围尚未与页面对齐，暂未开放到对话，请在对应功能页面查看'),
    ('POST /internal/mock/spec-upgrade/mark-removed', 0, 0, NULL,
     '变更类操作暂未开放到对话，请在对应功能页面执行')
ON CONFLICT (endpoint_key) DO NOTHING;
