-- Governance overlay rows that let the dialog read the dead-letter backlog counts through the
-- pass-through door (same posture as V383 for the prompt-template family).
--
-- Per-entry human verdict, in the words of the 11-05 adjudication ("the returned face must match
-- what the page shows a logged-in operator, and carry no credential material"):
--   * queue-backlog and class-distribution return COUNTS only (queue name / failure class and a
--     number). They are the operational numbers the dead-letter page itself opens with, they
--     contain no message bodies and no credential material, so the dialog may read them.
--   * GET /internal/mq-dead-letter/browse is deliberately NOT registered: it returns ledger rows
--     WITH message payloads. The page shows that face only under perm:system:dead-letter-read,
--     while the dialog read door serves every conversing operator — opening browse through the
--     door would return a wider face than the page itself. It stays refused until the door can
--     carry the page's permission gate.
--   * The POST entries (replay/void) keep their existing path: confirmation card, landing switch,
--     per-entry registered verdict. Nothing here widens the write side.
--
-- Flyway is not wired in this project: apply manually to the target database (DBA path in
-- production), then regenerate db/schema-init.sql with tools/ops/gen_schema_init.sh.

INSERT INTO tp_ai_api_access_governance
    (endpoint_key, scope_verified, reachable, ai_desc, exclude_reason)
VALUES
    ('GET /internal/mq-dead-letter/queue-backlog', 1, 1,
     '按队列统计死信积压数：省略 status 即「待处理」口径，每队列一行（count 求和=待处理总数）；指定 status（如 replayed/voided）时按该状态统计。问「有多少条待处理消息/死信积压多少」用本条目。',
     NULL),
    ('GET /internal/mq-dead-letter/class-distribution', 1, 1,
     '死信失败分型分布（每类一行计数）：可选 queueName 限定单队列、status 限定处理状态（省略=待处理口径）。问「死信都是什么原因/分几类各多少」用本条目。',
     NULL)
ON CONFLICT (endpoint_key) DO NOTHING;
