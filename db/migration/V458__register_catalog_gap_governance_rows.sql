-- Close the registration-coverage gap left by endpoints that landed after the 2026-11 full-ledger
-- batch. Verdicts come from the door's OWN live reachability (no reachability change: the
-- auto-served person-scoped read is documented, every other face lands closed with a
-- reader-facing reason). Generated and replay-safe (ON CONFLICT DO NOTHING) by
-- tools/ops/api_access_registration_coverage.py --emit-sql.
INSERT INTO tp_ai_api_access_governance
    (endpoint_key, scope_verified, reachable, ai_desc, exclude_reason)
VALUES
    ('GET /internal/ai-scenario-previews', 0, 0, NULL, '该查询的数据可见范围尚未与页面对齐，暂未开放到对话，请在对应功能页面查看'),
    ('GET /internal/documents/{id}/extract-token', 0, 0, NULL, '该接口涉及凭证或密钥材料，不开放到对话，请在对应管理页面操作'),
    ('GET /internal/programs/for-user', 1, 1, '读取当前登录人的项目集数据（按登录人自动限定，无需提供用户标识）', NULL),
    ('GET /internal/report-center/program-overview', 0, 0, NULL, '该查询的数据可见范围尚未与页面对齐，暂未开放到对话，请在对应功能页面查看'),
    ('POST /internal/ai-chat/drafts/group/{messageId}/apply', 0, 0, NULL, '对话窗自身的接口（草稿、图片、工具产物），请在界面直接使用'),
    ('POST /internal/ai-chat/drafts/group/{messageId}/confirm-token', 0, 0, NULL, '对话窗自身的接口（草稿、图片、工具产物），请在界面直接使用'),
    ('POST /internal/ai-chat/sessions/{sessionId}/undo', 0, 0, NULL, '对话窗自身的接口（草稿、图片、工具产物），请在界面直接使用'),
    ('POST /internal/ai-scenario-previews', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('POST /internal/cases/version-compare', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行'),
    ('POST /internal/documents/{id}/extraction-cancel', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行')
ON CONFLICT (endpoint_key) DO NOTHING;

-- Corpus seed: a documented auto-serve row without synonyms counts as a terms-empty entry
-- under the catalog corpus ratchet, so a row that opens by the door's live flag ships its
-- search synonyms in the same batch. Replay-safe assignment, only when still empty.
UPDATE tp_ai_api_access_governance
   SET search_terms = '项目集,我的项目集,项目集列表,programs,for-user'
 WHERE endpoint_key = 'GET /internal/programs/for-user'
   AND (search_terms IS NULL OR search_terms = '');
