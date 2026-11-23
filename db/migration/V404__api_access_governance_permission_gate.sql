-- Governance overlay gains its third ownership face: the page's functional permission.
--
-- The door could so far judge two ownership forms by itself: project anchor (membership-checked)
-- and person anchor (server re-stamp). Pages gated by a functional permission (@RequirePermission,
-- the same RBAC that admin's PermissionInterceptor enforces live per request) were a THIRD form
-- the catalog cannot derive — so the pass-through door either refused the whole entry (users could
-- not read from the dialog what they can read on the page: reported for the dead-letter browse) or,
-- if registered, served it to every conversing operator (wider than the page — the opposite leak).
--
-- This column closes both halves at the single point that serves the dialog: when a registered row
-- names required_permission, the door checks the acting operator against the SAME aggregation the
-- page interceptor uses (RoleService.permsOfUser, one source, no second implementation), and a
-- refusal names the permission point and where it is granted. Rows without the column keep the
-- previous posture untouched.
--
-- Applied by the same human per-entry verdict as every other row in this table. This migration
-- carries the dead-letter family (page face read from the live controllers):
--   * the two count reads registered earlier (V402) are backfilled to the page's read permission;
--   * the browse read (ledger rows with message payloads) is registered WITH that permission —
--     the reported blocker: the dialog could not get ledgerIds, so per-row void drafts were dead;
--   * the void POST entry is registered WITH its page action permission — landing still requires
--     the write switch (ai.api-access.write.enabled, shipped off) and a confirmation card;
--   * replay POST entries are NOT registered: batch re-execution against live exchanges is a
--     stronger production-change face and awaits its own named verdict.
-- Other already-registered families whose page carries a permission are inventoried as a ledger
-- for the registration window (per-entry human judgement stays the rule; no blind backfill).
--
-- Flyway is not wired in this project: apply manually to the target database (DBA path in
-- production), then regenerate db/schema-init.sql with tools/ops/gen_schema_init.sh.

ALTER TABLE tp_ai_api_access_governance
    ADD COLUMN IF NOT EXISTS required_permission VARCHAR(128);

COMMENT ON COLUMN tp_ai_api_access_governance.required_permission IS
    'Functional permission the serving page enforces (perm:* key); when set the dialog door requires the acting operator to hold it — same aggregation as the page interceptor, fail-closed when absent';

UPDATE tp_ai_api_access_governance
   SET required_permission = 'perm:system:dead-letter-read'
 WHERE endpoint_key IN ('GET /internal/mq-dead-letter/queue-backlog',
                        'GET /internal/mq-dead-letter/class-distribution')
   AND required_permission IS NULL;

INSERT INTO tp_ai_api_access_governance
    (endpoint_key, scope_verified, reachable, ai_desc, exclude_reason, required_permission)
VALUES
    ('GET /internal/mq-dead-letter/browse', 1, 1,
     '死信台账分页浏览（每行含消息正文快照与失败原因说明）：可按 queueName/failureClass/status 过滤，page/size 分页；ledgerId 取自本条目，供逐条作废草案使用。与死信队列页面同权限面，需要「死信台账查看」权限点。',
     NULL, 'perm:system:dead-letter-read'),
    ('POST /internal/mq-dead-letter/void', 1, 1,
     '作废单条死信台账行（ledgerId+reason 必带）：与页面作废按钮同权限面（需「死信台账作废」权限点），对话侧仍走草案确认卡+落地开关，确认前不执行。',
     NULL, 'perm:system:dead-letter-void')
ON CONFLICT (endpoint_key) DO NOTHING;

-- wording repair (W6: on-screen seed must not carry internal governance wording): rewrites the
-- browse description row on databases where the earlier wording of this migration already landed
-- (guarded by inequality so replays never churn the row).
UPDATE tp_ai_api_access_governance
   SET ai_desc = '死信台账分页浏览（每行含消息正文快照与失败原因说明）：可按 queueName/failureClass/status 过滤，page/size 分页；ledgerId 取自本条目，供逐条作废草案使用。与死信队列页面同权限面，需要「死信台账查看」权限点。'
 WHERE endpoint_key = 'GET /internal/mq-dead-letter/browse'
   AND ai_desc IS DISTINCT FROM '死信台账分页浏览（每行含消息正文快照与失败原因说明）：可按 queueName/failureClass/status 过滤，page/size 分页；ledgerId 取自本条目，供逐条作废草案使用。与死信队列页面同权限面，需要「死信台账查看」权限点。';
