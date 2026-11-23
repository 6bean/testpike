-- V438: help chapter for the program-set quality dashboard page (/program-dashboard)
-- in the report domain Q. Q is a multi-chapter domain, so the leaf carries a
-- chapter anchor (menuHelpCoverage contract); this row makes the
-- per-project quality readouts inside one program, drilldowns and server-side
-- caliber footnotes. Idempotent: (domain_code, sort_no) unique key,
-- ON CONFLICT DO NOTHING.
-- Usage: docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -f - < db/migration/V438__help_doc_program_dashboard_chapter.sql

INSERT INTO tp_help_doc (domain_code, title, body_md, sort_no, version) VALUES
('Q', '项目集质量大盘：集内各项目质量读数一屏对照', $mdq$## 项目集质量大盘

项目集把多个项目归成一组；本页在选中项目集时给出集内每个项目的质量读数，一屏对照，不用再逐项目切顶栏。

| 读数 | 一句话说明 |
| --- | --- |
| 通过率 | 该项目当前时间窗内用例执行通过率 |
| 缺陷燃尽 | 集内窗口期缺陷新增与关闭走势 |
| 旅程红绿灯 | 关键业务旅程最近一次执行的通过/失败概览 |
| 压测概览 | 该项目最近压测运行的结果摘要 |

## 怎么用
1. 顶栏切到某个项目集，本页自动按集内项目逐行展示；未选项目集时页面会指路到顶栏切换器。
2. 每行右侧提供下钻入口，跳到用例、批次、缺陷等清单页，并带着本集透镜只看集内数据。
3. 时间窗可在页内调整，表头会回显当前窗口范围。
4. 页底「统计口径」逐条列出口径定义，与报表中心同源，页面自身不重复计算。

**常用入口**：左侧菜单「跑完看什么 → 项目集质量大盘」。$mdq$, 6, '2.98')
ON CONFLICT (domain_code, sort_no) DO NOTHING;
