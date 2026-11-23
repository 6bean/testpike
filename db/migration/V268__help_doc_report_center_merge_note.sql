-- Migration: inline merge note in the "report center" help chapter after the
-- AN-3 report-domain merge (half B: the snapshot-ledger leaf folded into the
-- reports page as the "exports & snapshots" tab).
-- Why: the chapter was written while the ledger was a standalone menu leaf.
-- Users landing on the merged page should be told where the old entry lives
-- now (one sentence), so the help text matches the sidebar.
-- Scope: pure documentation sync — zero schema change, zero new chapter; the
-- row keeps its (domain_code='Q', sort_no=4) so every leaf "?" anchor stays
-- green with zero re-wiring (same shape as the half-A note in V267).
-- Discipline: user-facing bodies carry zero task codes / table names / config
-- keys; idempotent via a guarded append (NOT LIKE on the appended sentence),
-- so re-running this migration never duplicates the note.
-- Usage: docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -v ON_ERROR_STOP=1 -f - < db/migration/V268__help_doc_report_center_merge_note.sql

UPDATE tp_help_doc
SET body_md = body_md || E'\n## 菜单位置说明\n报表中心不再单设菜单入口：它并进「统计报表」页面的「导出与快照」页签，周期快照台账、详情抽屉与 CSV/PDF 导出同页切换，看板、覆盖矩阵、快照台账选一次报表口径即可通读。\n',
    version = '2.112',
    updated_at = now()
WHERE domain_code = 'Q' AND sort_no = 4
  AND body_md NOT LIKE '%报表中心不再单设菜单入口%';
