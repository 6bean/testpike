-- Migration: close the api-access governance ledger gaps and give the local
-- storage root a usable factory default for a fresh install.
--
-- Part 1 (B3): the registration coverage tool audited 105 catalog endpoints
-- that carry no row in tp_ai_api_access_governance, so the door answered
-- them with the generic "not registered" refusal even though a verdict
-- exists. Rows are produced by tools/ops/api_access_registration_coverage.py
-- --emit-sql; every entry keeps the door's own live flags (anchored reads
-- document what is already auto-served; everything else lands closed with a
-- reader-facing reason). No reachability changes: opening a closed entry
-- still needs its own named verification, write landing still needs
-- scope_verified=1 + the write switch + the confirmation card.
--
-- Part 2 (E4): tp_sys_config.storage.local-root was seeded as an empty
-- string, which makes every local-provider consumer read "storage is not
-- configured" on a fresh install. Seed the row idempotently and rewrite only
-- the legacy empty default to the working relative root; a value an operator
-- set explicitly is preserved untouched.

INSERT INTO tp_ai_api_access_governance
    (endpoint_key, scope_verified, reachable, ai_desc, exclude_reason)
VALUES
    ('DELETE /internal/appauto/elements/{id}', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('DELETE /internal/session-capture-sessions/{sessionId}', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('DELETE /internal/session-states/{id}', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('DELETE /internal/site-profiles/{id}', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('GET /internal/ai-batches/latest', 1, 1, '按项目范围查询AI 批次数据（项目由会话自动带入，无需手填）', NULL),
    ('GET /internal/ai-chat/drafts/group/pending', 0, 0, NULL, '对话窗自身的接口（草稿、图片、工具产物），请在界面直接使用'),
    ('GET /internal/ai-chat/drafts/pending-count', 0, 0, NULL, '对话窗自身的接口（草稿、图片、工具产物），请在界面直接使用'),
    ('GET /internal/ai-chat/messages/{messageId}/status', 0, 0, NULL, '对话窗自身的接口（草稿、图片、工具产物），请在界面直接使用'),
    ('GET /internal/ai-chat/sessions/{sessionId}/chat-images', 0, 0, NULL, '对话窗自身的接口（草稿、图片、工具产物），请在界面直接使用'),
    ('GET /internal/ai-tasks/latest-single', 1, 1, '按项目范围查询AI 任务数据（项目由会话自动带入，无需手填）', NULL),
    ('GET /internal/ai-tasks/live-by-requirements', 1, 1, '按项目范围查询AI 任务数据（项目由会话自动带入，无需手填）', NULL),
    ('GET /internal/ai-tasks/{taskId}/process-events', 0, 0, NULL, '该查询的数据可见范围尚未与页面对齐，暂未开放到对话，请在对应功能页面查看'),
    ('GET /internal/appauto/devices/{deviceId}/thumbnail', 0, 0, NULL, '该查询的数据可见范围尚未与页面对齐，暂未开放到对话，请在对应功能页面查看'),
    ('GET /internal/appauto/elements', 1, 1, '按项目范围查询应用自动化数据（项目由会话自动带入，无需手填）', NULL),
    ('GET /internal/appauto/elements/{id}', 0, 0, NULL, '该查询的数据可见范围尚未与页面对齐，暂未开放到对话，请在对应功能页面查看'),
    ('GET /internal/batches/{id}/process-events', 0, 0, NULL, '该查询的数据可见范围尚未与页面对齐，暂未开放到对话，请在对应功能页面查看'),
    ('GET /internal/batches/{id}/process-runs', 0, 0, NULL, '该查询的数据可见范围尚未与页面对齐，暂未开放到对话，请在对应功能页面查看'),
    ('GET /internal/cluster/pools/scale-events', 0, 0, NULL, '该查询的数据可见范围尚未与页面对齐，暂未开放到对话，请在对应功能页面查看'),
    ('GET /internal/cluster/pools/scale-plan', 0, 0, NULL, '该查询的数据可见范围尚未与页面对齐，暂未开放到对话，请在对应功能页面查看'),
    ('GET /internal/cluster/pools/watermarks', 0, 0, NULL, '该查询的数据可见范围尚未与页面对齐，暂未开放到对话，请在对应功能页面查看'),
    ('GET /internal/credentials/{id}/test-events', 0, 0, NULL, '该接口涉及凭证或密钥材料，不开放到对话，请在对应管理页面操作'),
    ('GET /internal/credentials/{id}/test-events/{eventId}/full', 0, 0, NULL, '该接口涉及凭证或密钥材料，不开放到对话，请在对应管理页面操作'),
    ('GET /internal/credentials/{id}/test-events/{eventId}/reveal', 0, 0, NULL, '该接口涉及凭证或密钥材料，不开放到对话，请在对应管理页面操作'),
    ('GET /internal/credentials/{id}/test-runs', 0, 0, NULL, '该接口涉及凭证或密钥材料，不开放到对话，请在对应管理页面操作'),
    ('GET /internal/documents/{id}/chunks/{chunkId}/context', 0, 0, NULL, '该查询的数据可见范围尚未与页面对齐，暂未开放到对话，请在对应功能页面查看'),
    ('GET /internal/documents/{id}/extraction-events', 0, 0, NULL, '该查询的数据可见范围尚未与页面对齐，暂未开放到对话，请在对应功能页面查看'),
    ('GET /internal/documents/{id}/extraction-runs', 0, 0, NULL, '该查询的数据可见范围尚未与页面对齐，暂未开放到对话，请在对应功能页面查看'),
    ('GET /internal/documents/{id}/spec-extraction-events', 0, 0, NULL, '该查询的数据可见范围尚未与页面对齐，暂未开放到对话，请在对应功能页面查看'),
    ('GET /internal/documents/{id}/spec-extraction-runs', 0, 0, NULL, '该查询的数据可见范围尚未与页面对齐，暂未开放到对话，请在对应功能页面查看'),
    ('GET /internal/explore/sessions/{id}/process-events', 0, 0, NULL, '该查询的数据可见范围尚未与页面对齐，暂未开放到对话，请在对应功能页面查看'),
    ('GET /internal/explore/sessions/{id}/process-runs', 0, 0, NULL, '该查询的数据可见范围尚未与页面对齐，暂未开放到对话，请在对应功能页面查看'),
    ('GET /internal/judge/evalsets', 0, 0, NULL, '该查询的数据可见范围尚未与页面对齐，暂未开放到对话，请在对应功能页面查看'),
    ('GET /internal/judge/rubrics', 0, 0, NULL, '该查询的数据可见范围尚未与页面对齐，暂未开放到对话，请在对应功能页面查看'),
    ('GET /internal/judge/runs', 0, 0, NULL, '该查询的数据可见范围尚未与页面对齐，暂未开放到对话，请在对应功能页面查看'),
    ('GET /internal/linkage-impacts/by-drafts', 0, 0, NULL, '该查询的数据可见范围尚未与页面对齐，暂未开放到对话，请在对应功能页面查看'),
    ('GET /internal/linkage-impacts/pending', 0, 0, NULL, '该查询的数据可见范围尚未与页面对齐，暂未开放到对话，请在对应功能页面查看'),
    ('GET /internal/linkage-proposals', 0, 0, NULL, '该查询的数据可见范围尚未与页面对齐，暂未开放到对话，请在对应功能页面查看'),
    ('GET /internal/login-exempt/{envId}', 0, 0, NULL, '该查询的数据可见范围尚未与页面对齐，暂未开放到对话，请在对应功能页面查看'),
    ('GET /internal/mcp-tokens/{tokenId}/usage', 0, 0, NULL, '该接口涉及凭证或密钥材料，不开放到对话，请在对应管理页面操作'),
    ('GET /internal/page-write-profiles/page', 1, 1, '按项目范围查询page-write-profiles数据（项目由会话自动带入，无需手填）', NULL),
    ('GET /internal/page-write-profiles/risk-pages', 1, 1, '按项目范围查询page-write-profiles数据（项目由会话自动带入，无需手填）', NULL),
    ('GET /internal/session-capture-sessions/stage/{token}/names', 0, 0, NULL, '该接口涉及凭证或密钥材料，不开放到对话，请在对应管理页面操作'),
    ('GET /internal/session-capture-sessions/{sessionId}', 0, 0, NULL, '该查询的数据可见范围尚未与页面对齐，暂未开放到对话，请在对应功能页面查看'),
    ('GET /internal/session-capture-sessions/{sessionId}/candidates', 0, 0, NULL, '该查询的数据可见范围尚未与页面对齐，暂未开放到对话，请在对应功能页面查看'),
    ('GET /internal/session-states', 1, 1, '按项目范围查询session-states数据（项目由会话自动带入，无需手填）', NULL),
    ('GET /internal/session-states/page', 1, 1, '按项目范围查询session-states数据（项目由会话自动带入，无需手填）', NULL),
    ('GET /internal/session-states/resolve', 1, 1, '按项目范围查询session-states数据（项目由会话自动带入，无需手填）', NULL),
    ('GET /internal/session-states/{id}', 0, 0, NULL, '该查询的数据可见范围尚未与页面对齐，暂未开放到对话，请在对应功能页面查看'),
    ('GET /internal/session-states/{id}/probe-events', 0, 0, NULL, '该接口服务于系统内部联动与健康检查，不是业务查询面，无需在对话使用'),
    ('GET /internal/session-states/{id}/probe-events/{eventId}/full', 0, 0, NULL, '该接口服务于系统内部联动与健康检查，不是业务查询面，无需在对话使用'),
    ('GET /internal/session-states/{id}/probe-events/{eventId}/reveal', 0, 0, NULL, '该接口服务于系统内部联动与健康检查，不是业务查询面，无需在对话使用'),
    ('GET /internal/session-states/{id}/probe-runs', 0, 0, NULL, '该接口服务于系统内部联动与健康检查，不是业务查询面，无需在对话使用'),
    ('GET /internal/site-profiles', 1, 1, '按项目范围查询site-profiles数据（项目由会话自动带入，无需手填）', NULL),
    ('GET /internal/site-profiles/{id}', 0, 0, NULL, '该接口用于文件导出或下载，请在对应页面的下载入口使用'),
    ('GET /internal/site-survey-runs', 1, 1, '按项目范围查询site-survey-runs数据（项目由会话自动带入，无需手填）', NULL),
    ('GET /internal/site-survey-runs/by-session/{sessionId}', 0, 0, NULL, '该查询的数据可见范围尚未与页面对齐，暂未开放到对话，请在对应功能页面查看'),
    ('GET /internal/site-survey-runs/{runId}/diff-proposal', 1, 1, '按项目范围查询site-survey-runs数据（项目由会话自动带入，无需手填）', NULL),
    ('GET /internal/web-elements', 1, 1, '按项目范围查询web-elements数据（项目由会话自动带入，无需手填）', NULL),
    ('GET /internal/web-elements/heal-evidence', 0, 0, NULL, '该查询的数据可见范围尚未与页面对齐，暂未开放到对话，请在对应功能页面查看'),
    ('GET /internal/web-elements/health', 1, 1, '按项目范围查询web-elements数据（项目由会话自动带入，无需手填）', NULL),
    ('GET /internal/web-elements/pages', 1, 1, '按项目范围查询web-elements数据（项目由会话自动带入，无需手填）', NULL),
    ('GET /internal/web-elements/prompt-summary', 1, 1, '按项目范围查询web-elements数据（项目由会话自动带入，无需手填）', NULL),
    ('GET /internal/web-elements/storms', 1, 1, '按项目范围查询web-elements数据（项目由会话自动带入，无需手填）', NULL),
    ('GET /internal/web-elements/survey/{sessionId}/process-events', 0, 0, NULL, '该查询的数据可见范围尚未与页面对齐，暂未开放到对话，请在对应功能页面查看'),
    ('GET /internal/web-elements/{id}', 0, 0, NULL, '该查询的数据可见范围尚未与页面对齐，暂未开放到对话，请在对应功能页面查看'),
    ('GET /internal/web-elements/{id}/heal-evidence', 0, 0, NULL, '该查询的数据可见范围尚未与页面对齐，暂未开放到对话，请在对应功能页面查看'),
    ('GET /internal/web-elements/{id}/referencing-cases', 0, 0, NULL, '该查询的数据可见范围尚未与页面对齐，暂未开放到对话，请在对应功能页面查看'),
    ('PATCH /internal/ai-chat/drafts/{id}/checked-subset', 0, 0, NULL, '对话窗自身的接口（草稿、图片、工具产物），请在界面直接使用'),
    ('POST /internal/ai-chat/drafts/group/apply', 0, 0, NULL, '对话窗自身的接口（草稿、图片、工具产物），请在界面直接使用'),
    ('POST /internal/ai-chat/drafts/group/confirm-token', 0, 0, NULL, '对话窗自身的接口（草稿、图片、工具产物），请在界面直接使用'),
    ('POST /internal/ai-tasks/{taskId}/cancel', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('POST /internal/appauto/elements', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('POST /internal/appauto/elements/capture', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('POST /internal/credentials/{id}/test', 0, 0, NULL, '该接口涉及凭据材料，不开放到对话；请在凭据管理页面操作'),
    ('POST /internal/documents/{id}/confirm-all-extraction', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('POST /internal/judge/runs', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('POST /internal/linkage-proposals', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('POST /internal/login-exempt/{envId}/review', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('POST /internal/mcp-confirm/confirm', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('POST /internal/mcp-confirm/drafts/script-writeback', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('POST /internal/page-write-profiles/case-draft', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('POST /internal/session-capture-sessions', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('POST /internal/session-capture-sessions/claim', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('POST /internal/session-capture-sessions/stage', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('POST /internal/session-capture-sessions/{sessionId}/commit', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('POST /internal/session-capture-sessions/{sessionId}/commit-values', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('POST /internal/session-capture-sessions/{sessionId}/report', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('POST /internal/session-states', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('POST /internal/session-states/{id}/probe', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('POST /internal/site-profiles', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('POST /internal/site-survey-runs/{profileId}/dispatch', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('POST /internal/web-elements', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('POST /internal/web-elements/census/ledger-drafts', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('POST /internal/web-elements/census/script-ref-drafts', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('POST /internal/web-elements/evidence-grant', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('POST /internal/web-elements/retention/run', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('POST /internal/web-elements/survey', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('PUT /internal/appauto/elements/{id}', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('PUT /internal/login-exempt/{envId}', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('PUT /internal/session-states/{id}', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('PUT /internal/session-states/{id}/enabled', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('PUT /internal/site-profiles/{id}', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('PUT /internal/web-elements/{id}/anchors', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('PUT /internal/web-elements/{id}/name', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('PUT /internal/web-elements/{id}/state', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行')
ON CONFLICT (endpoint_key) DO NOTHING;

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES ('storage.local-root', 'data/local-storage', '存储', 'string', 0,
        '本地模式落盘根目录（storage.provider=local 必填；空=未配置）')
ON CONFLICT (config_key) DO UPDATE SET config_value = 'data/local-storage'
WHERE tp_sys_config.config_value IS NULL OR tp_sys_config.config_value = '';
