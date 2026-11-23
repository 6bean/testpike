-- Assertion truth-source, level 2 : with the switch on
-- (factory default), the stage2 action-JSON generation pass always retrieves
-- the project's DDL dictionary rows (doc_category=DDL_DICT through the
-- knowledge search) and injects the table-structure reference into the prompt,
-- so DB-facing assertion wording cites real table/column names instead of
-- invented ones. This grounding is deliberately decoupled from the legacy
-- ai.sql-gen.enabled gate: that int-face gate alone keeps deciding whether the
-- sql.assert/sql.query action family is offered to the model (action
-- availability zero drift). With this row off the segment rides the legacy
-- gate only, restoring the exact pre-extension prompt. Retrieval failures and
-- dictionary-less projects inject nothing and never interrupt generation.
--
-- Flyway is not wired in this project: apply manually to the target database
-- (DBA path in production), then regenerate db/schema-init.sql with
-- tools/ops/gen_schema_init.sh.

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.gen.truth-source.ddl-dict', 'true', '系统', 'bool', 0,
     'DB 断言真值注入开关（默认开启；开启后脚本生成固定注入本项目 DDL 字典检索到的表结构参考，表名列名以字典为据、禁凭记忆猜；与 SQL 动作生成开关相互独立，后者仅控制系统是否产出 sql.assert/sql.query 步骤；字典无行或检索失败时不注入、生成流程不受影响；修改即时生效）')
ON CONFLICT (config_key) DO NOTHING;
