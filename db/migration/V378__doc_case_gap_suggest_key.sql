-- V378: case-gap suggestion switch — the actionable half of the requirement
-- list quality loop. Gates GET-side core listUncovered(projectId,page,size),
-- the server-computed "requirements with no active case" set (same coverage
-- wording as the coverage matrix: retired requirements and archived /
-- soft-deleted case links do not count). Factory default on (AI features
-- ship enabled); while off the query is refused with a pointer naming the
-- settings page and this key. One-click handoff pre-fills the existing
-- batch-creation entry — the ingest checkbox and review human gates stay
-- exactly as before.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.doc.case-gap-suggest', 'true', '系统', 'bool', 0,
     '无用例需求点建议开关（默认开启；开启时可查询本项目尚无任何有效用例覆盖的需求点清单，用于一键补生成用例；关闭时查询被拒绝并提示在本系统设置中打开。修改即时生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;
