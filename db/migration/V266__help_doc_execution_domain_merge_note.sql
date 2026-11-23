-- Migration: inline merge notes in the two "execution" domain help chapters
-- after the AN-2 menu consolidation (running-tasks leaf folded into the
-- execution page as a tab; the timed-trigger ledger leaf folded into the
-- batches page as a tab).
-- Why: the chapters were written while those views were standalone menu
-- leaves. Users landing on the merged pages should be told where the old
-- entries live now (one sentence each), so the help text matches the sidebar.
-- Scope: pure documentation sync — zero schema change, zero new chapter; the
-- two rows keep their (domain_code='I', sort_no) so every leaf "?" anchor
-- stays green with zero re-wiring (V252 anchors resolve by domain+sort).
-- Discipline: user-facing bodies carry zero task codes / table names / config
-- keys; idempotent via a guarded append (NOT LIKE on the appended sentence),
-- so re-running this migration never duplicates the note.
-- Usage: docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -v ON_ERROR_STOP=1 -f - < db/migration/V266__help_doc_execution_domain_merge_note.sql

UPDATE tp_help_doc
SET body_md = body_md || E'\n## 菜单位置说明\n跨批次的运行中任务不再单设菜单入口：它并进「执行与试运行」页面的「运行中任务（跨批次）」页签，任务取消、水位查看都在同一页，切页签不用来回跳。\n',
    version = '2.110',
    updated_at = now()
WHERE domain_code = 'I' AND sort_no = 4
  AND body_md NOT LIKE '%运行中任务（跨批次）%';

UPDATE tp_help_doc
SET body_md = body_md || E'\n## 菜单位置说明\n定时触发记录不再单设菜单入口：它并进「批次管理」页面的「定时触发记录」页签，按项目看每次定时触发的来龙去脉，与批次列表同页切换。\n',
    version = '2.110',
    updated_at = now()
WHERE domain_code = 'I' AND sort_no = 1
  AND body_md NOT LIKE '%定时触发记录%';
