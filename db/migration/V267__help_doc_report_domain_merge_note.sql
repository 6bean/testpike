-- Migration: inline merge note in the "reports board" help chapter after the
-- AN-3 report-domain merge (the coverage-matrix leaf folded into the reports
-- page as a tab).
-- Why: the chapter was written while the matrix was a standalone menu leaf.
-- Users landing on the merged page should be told where the old entry lives
-- now (one sentence), so the help text matches the sidebar.
-- Scope: pure documentation sync — zero schema change, zero new chapter; the
-- row keeps its (domain_code='Q', sort_no=3) so every leaf "?" anchor stays
-- green with zero re-wiring (V252 anchors resolve by domain+sort; live-scan
-- proof: V256 seeds Q domain = 2:summary-report, 3:reports-board, 4:report-
-- center, 5:coverage-matrix — the note lands in the reports-board chapter).
-- Discipline: user-facing bodies carry zero task codes / table names / config
-- keys; idempotent via a guarded append (NOT LIKE on the appended sentence),
-- so re-running this migration never duplicates the note.
-- Usage: docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -v ON_ERROR_STOP=1 -f - < db/migration/V267__help_doc_report_domain_merge_note.sql

UPDATE tp_help_doc
SET body_md = body_md || E'\n## 菜单位置说明\n覆盖矩阵不再单设菜单入口：它并进「统计报表」页面的「覆盖矩阵」页签，覆盖率看板与需求点×用例的盲区清单同页切换，选一次项目两处口径一致。\n',
    version = '2.111',
    updated_at = now()
WHERE domain_code = 'Q' AND sort_no = 3
  AND body_md NOT LIKE '%覆盖矩阵不再单设菜单入口%';
