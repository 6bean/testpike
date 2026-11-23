-- Migration: governance ledger row for the route-axis replay verdict paging read
-- . The endpoint feeds the admin quality-dashboard
-- bridge that lists golden route-replay verdicts newest-first. The read is not
-- yet aligned with a per-project page surface, so it follows the V411 precedent
-- for the sibling /latest read and stays blocked to the dialog channel until
-- the page-side visibility review lands.
-- Applied manually to this chain's slot DB (Flyway is not enabled; db/migration
-- is reference), then db/schema-init.sql regenerated via tools/ops/gen_schema_init.sh.

INSERT INTO tp_ai_api_access_governance
    (endpoint_key, scope_verified, reachable, ai_desc, exclude_reason)
VALUES
    ('GET /internal/behavior/golden-drift/route-verdicts', 0, 0, NULL,
     '该查询的数据可见范围尚未与页面对齐，暂未开放到对话，请在对应功能页面查看')
ON CONFLICT (endpoint_key) DO NOTHING;
