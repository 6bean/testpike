-- Extraction-participation whitelist (BA batch decision 9, requirement-document
-- category): categories whose uploads default the per-document
-- "participate in requirement extraction" switch ON. Hot-updatable -- edits
-- take effect on subsequent uploads, no restart.
--
-- Shipped value is an equivalence fold-in of the historical behaviour: the
-- switch defaulted ON for TEST_SPEC and DDL_DICT and OFF for REFERENCE, and
-- and cases are derived from) joins the ON side at the same door as TEST_SPEC.
-- The adjudication brief names the default "TEST_SPEC,REQUIREMENT_DOC"; the
-- uploads that participate today, so DDL_DICT is folded in at the same value
-- (self-adjudicated, recorded in PROGRESS). REFERENCE stays out exactly as
-- today (its switch defaults off and stays user-tunable per document).
--
-- Flyway is not wired in this project: apply manually to the target database
-- (DBA path in production), then regenerate db/schema-init.sql with
-- tools/ops/gen_schema_init.sh.

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.doc.extraction-allow-categories', 'TEST_SPEC,REQUIREMENT_DOC,DDL_DICT', '系统', 'string', 0, '默认参与需求提取的文档分类（逗号分隔）。上传时分类在此清单内的文档，「参与需求提取」开关默认打开；不在清单内的默认关闭，可在文档列表逐行调整。修改后对之后的上传生效，已上传文档不受影响')
ON CONFLICT (config_key) DO NOTHING;
