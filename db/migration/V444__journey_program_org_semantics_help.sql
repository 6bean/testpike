-- Help chapter for the program-organization semantics (Y wave, task deliverable).
-- Why: the deployment doc now carries the three organizational rules (single owner per
-- integration program, P0 golden journeys capped at 10-20, contract consumption strictly
-- via subscriptions), but the in-app help center had no chapter explaining them, so users
-- hitting ownership or subscription errors had no self-service reference. This chapter is
-- the user-facing twin of deployment doc section 8.
-- Discipline: body carries zero table names / config keys / feature codes (plain operational
-- language only); idempotent via ON CONFLICT (domain_code, sort_no) DO UPDATE.
-- Mirrors V382 (same domain, next sort slot).
-- Usage: apply with psql against the chain's own database (Flyway is not enabled);
-- main-slot replay and first-install artifact regeneration are main-chain obligations.

INSERT INTO tp_help_doc (domain_code, title, body_md, sort_no, version, updated_at)
VALUES
('I', '集成组负责人、黄金旅程与契约订阅的口径', $mdq$
## 这三条口径管什么
项目集（集成组）把多个项目拉到同一张协作视图里，但它只是透镜：所有数据仍归属各自项目。为了让跨项目协作不出乱子，团队约定了三条组织口径，本页说明它们在系统里的实际表现。

## 集成组只有一个负责人
每个集成组设唯一负责人，负责成员的加入与退出、集内命名冲突的处置。负责人的更换在项目管理页面完成并留痕。如果你在集内遇到「无权调整成员」之类的拒绝提示，先找集成组负责人办理，而不是反复尝试。

## 黄金旅程控制在 10~20 条
以准出签字为目标的跨项目黄金旅程，总量建议控制在 10~20 条以内。旅程是编排视图，不是数据的容器：条数失控后，准出评审会退化成逐段核对批次，评审成本远大于收益。更多的回归诉求请放到批次计划或接口集回归里，不要挤进旅程册。

## 跨项目用契约，只走订阅
需要消费另一个项目的接口契约时，用「订阅」而不是复制：在订阅入口选择同集内的提供方项目和它的契约，系统会自动跟踪提供方最新有效版本；提供方改版后会收到变更通知，确认后你的项目就跟随到新版。直接把契约文本复制到项目里会造成版本失联，评审和变更分析都不会认。
$mdq$,
8, '2.187', now())
ON CONFLICT (domain_code, sort_no) DO UPDATE
SET title = EXCLUDED.title,
    body_md = EXCLUDED.body_md,
    version = EXCLUDED.version,
    updated_at = EXCLUDED.updated_at;
