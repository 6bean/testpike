-- Semantic acceptance-criteria reconciliation : with the
-- switch on (factory default), each acceptance criterion is reconciled
-- against the generated case text by embedding cosine — a natural paraphrase
-- of the criterion counts as covered instead of being reported as a miss, and
-- the regeneration feedback only names genuinely uncovered criteria. When the
-- embedding service is unavailable the check degrades automatically to the
-- previous keyword-fragment matching and the generation flow is never
-- interrupted. The threshold is the cosine verdict line (0.5-0.99, default
-- 0.82): lower treats more rewrites as covered, higher judges stricter.
-- Both rows are read per generation run (hot update, no restart).
--
-- Flyway is not wired in this project: apply manually to the target database
-- (DBA path in production), then regenerate db/schema-init.sql with
-- tools/ops/gen_schema_init.sh.

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.gen.coverage-semantic.enabled', 'true', '系统', 'bool', 0,
     '验收标准语义对账开关（默认开启；开启后按向量相似度判断每条验收标准是否被生成用例覆盖，标准被换说法改写也算覆盖；embedding 服务不可用时自动退回关键词片段匹配，生成流程不受影响；修改即时生效）'),
    ('ai.gen.coverage-semantic-threshold', '0.82', '系统', 'string', 0,
     '验收标准语义相似度判定线（取值 0.5-0.99，默认 0.82；相似度达到该值视为已覆盖，数值越小越宽松、越大越严格；仅在语义对账开启时生效；填写非数字或超出取值范围时按 0.82 执行；修改即时生效）')
ON CONFLICT (config_key) DO NOTHING;
