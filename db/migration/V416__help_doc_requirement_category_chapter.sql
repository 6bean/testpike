-- Help chapter anchor for the document category taxonomy .
-- (domain F, sort_no 1). REQUIREMENT_DOC joined the category enum in V415, so the
-- chapter body must describe all four categories and the inline category switch,
-- otherwise users meet a new upload-dialog option with no help coverage.
-- Discipline: appended section carries operation info only (no internal wording);
-- idempotent via a NOT LIKE guard on the appended marker, no replay churn.
-- Usage: docker exec -i testpike-postgres psql -U testpike -d testpike -v ON_ERROR_STOP=1 -f - < this file

UPDATE tp_help_doc
SET body_md = body_md || E'\n\n## 文档类别有哪几种\n'
    || E'上传对话框里每份文档要选一个类别，共四种，决定它在这条流水线里的角色：\n'
    || E'- 需求文档：真正向下推出需求点和用例的源文档（如 PRD、需求说明书）。选这个类别上传、解析成功后，可直接发起需求点提取。\n'
    || E'- 测试规格说明：描述测试范围与规格的文档，同样可以发起需求点提取。\n'
    || E'- 数据字典：字段与表结构说明文档。\n'
    || E'- 参考佐证：仅供查阅引用的背景资料，不用于发起提取。\n'
    || E'类别可以在列表里逐行修改。修改类别只影响之后能否发起提取，不会改动已确认入库的需求点。',
    version = '2.93',
    updated_at = now()
WHERE domain_code = 'F' AND sort_no = 1
  AND body_md NOT LIKE '%文档类别有哪几种%';
