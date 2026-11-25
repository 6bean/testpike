-- Migration: register governance verdicts for the two mock spec-import
-- advice endpoints that landed with the contract-batch mock suite. A fresh
-- install replays only schema-init/migrations, so a catalog entry without a
-- seed row answers with the generic "not registered" refusal and the
-- coverage contract goes red there even though live audits pass on a
-- database where the rows were registered out-of-band. No reachability
-- change: the read is preview plumbing for the space-workbench import
-- wizard and the POST is the AI job's internal backfill intake - both stay
-- closed on the dialogue door, and the human confirm gate is untouched.

INSERT INTO tp_ai_api_access_governance
    (endpoint_key, scope_verified, reachable, ai_desc, exclude_reason)
VALUES
    ('GET /internal/mock/stub/drafts-advised', 0, 0, NULL,
     '该接口服务于空间工作台导入向导的数据装配，不是业务查询面，无需在对话使用'),
    ('POST /internal/mock/spec-import/verdict-refine', 0, 0, NULL,
     '变更类操作暂未开放到对话，请在对应功能页面执行')
ON CONFLICT (endpoint_key) DO NOTHING;
