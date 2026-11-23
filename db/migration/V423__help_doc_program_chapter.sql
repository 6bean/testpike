-- V423: help chapter for the program-set console (project grouping) in the
-- multi-project domain. The domain is multi-chapter, so every leaf mapped to
-- it carries a chapter anchor (menuHelpCoverage contract); this row makes the
-- Idempotent: (domain_code, sort_no) unique key, ON CONFLICT DO NOTHING.
-- Usage: docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -f - < db/migration/V423__help_doc_program_chapter.sql

INSERT INTO tp_help_doc (domain_code, title, body_md, sort_no, version) VALUES
('H', '项目集管理与跨项目汇总', $mdq$## 项目集管理与跨项目汇总

项目集把多个项目归成一组，用于跨项目的统一授权与汇总视角；项目集本身不复制数据，项目及其内容仍留在原位。

| 能力 | 一句话说明 |
| --- | --- |
| 新建/编辑项目集 | 名称 + 描述，随时可改 |
| 归档与恢复 | 归档后不再出现在日常分组视角，数据不受影响 |
| 删除项目集 | 只解除分组与项目集授权，项目本身不受影响 |
| 挂载项目 | 在「挂载项目」抽屉里把可见项目加入或移出本集 |
| 成员与角色 | 在「成员」抽屉里搜索用户加入，角色分成员/管理者两档 |

**常用入口**：左侧菜单「系统管理 → 项目集管理」。项目集为空时界面与不使用项目集时完全一致，零打扰。$mdq$, 90, '2.41')
ON CONFLICT (domain_code, sort_no) DO NOTHING;
