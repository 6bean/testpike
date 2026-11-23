-- Migration: inline merge notes in the four mocking help chapters after the
-- AN-4 merge (docs / scenarios / recording / inspector menu leaves folded
-- into the per-space workbench page /mock/spaces/:id as tabs).
-- Why: these chapters were written while each screen was a standalone menu
-- leaf. Users landing from a retired address (the redirect keeps them working)
-- should be told where the old entry lives now (one sentence per chapter), so
-- the help text matches the sidebar.
-- Scope: pure documentation sync — zero schema change, zero new chapter; every
-- row keeps its (domain_code='T', sort_no) so every surviving leaf "?" anchor
-- stays green with zero re-wiring (same shape as V267/V268 notes).
-- Discipline: user-facing bodies carry zero task codes / table names / config
-- keys; idempotent via guarded appends (NOT LIKE on each appended sentence),
-- so re-running this migration never duplicates a note.
-- Usage: docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -v ON_ERROR_STOP=1 -f - < db/migration/V269__help_doc_mock_leaves_merge_note.sql

-- Inspector chapter: the traffic screen is now the workbench's last tab.
UPDATE tp_help_doc
SET body_md = body_md || E'\n## 菜单位置说明\n流量检视不再单设菜单入口：它并进「仿真空间」列表逐行打开的空间工作台页签，版本装载与命中/落空流量在同页切换查看；从旧地址进入会自动落到对应空间的工作台，未带空间时落在空间列表选一个即可。\n',
    version = '2.113',
    updated_at = now()
WHERE domain_code = 'T' AND sort_no = 2
  AND body_md NOT LIKE '%流量检视不再单设菜单入口%';

-- Docs chapter: the rendered-rule document is now a workbench tab.
UPDATE tp_help_doc
SET body_md = body_md || E'\n## 菜单位置说明\n仿真文档不再单设菜单入口：它并进「仿真空间」列表逐行打开的空间工作台「仿真文档」页签，规则文档渲染与粘贴 curl 建规则同页进行；从旧地址进入会自动落到对应空间的工作台。\n',
    version = '2.113',
    updated_at = now()
WHERE domain_code = 'T' AND sort_no = 5
  AND body_md NOT LIKE '%仿真文档不再单设菜单入口%';

-- Scenario chapter: the state-machine list is now a workbench tab (detail keeps its own page).
UPDATE tp_help_doc
SET body_md = body_md || E'\n## 菜单位置说明\n状态场景不再单设菜单入口：它并进「仿真空间」列表逐行打开的空间工作台「状态场景」页签，场景列表分页与筛选同页在场，点开场景仍进入独立详情页；从旧地址进入会自动落到对应空间的工作台。\n',
    version = '2.113',
    updated_at = now()
WHERE domain_code = 'T' AND sort_no = 6
  AND body_md NOT LIKE '%状态场景不再单设菜单入口%';

-- Recording chapter: record & replay is now a workbench tab.
UPDATE tp_help_doc
SET body_md = body_md || E'\n## 菜单位置说明\n录制回放不再单设菜单入口：它并进「仿真空间」列表逐行打开的空间工作台「录制回放」页签，开盒录音与封盒回放成规则同页进行；从旧地址进入会自动落到对应空间的工作台。\n',
    version = '2.113',
    updated_at = now()
WHERE domain_code = 'T' AND sort_no = 7
  AND body_md NOT LIKE '%录制回放不再单设菜单入口%';
