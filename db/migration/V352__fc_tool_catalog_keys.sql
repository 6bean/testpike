-- Migration: AP-5 deferred tool-catalog assembly keys .
-- Semantic: above ai.tool.catalog-threshold executable tools in a project's FC
-- assembly, the prompt keeps only ai.tool.catalog-core-tools resident plus the
-- built-in search_tools meta-tool; the rest ride a compact catalog line and are
-- materialized on demand (max ai.tool.catalog-expand-max per search).
-- Routing/authorization is unaffected: every candidate stays dispatch-eligible.
-- Applied manually to the dev DB (Flyway is not enabled; db/migration is
-- reference), then db/schema-init.sql regenerated via tools/ops/gen_schema_init.sh.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.tool.catalog-threshold', '20', '系统', 'int', 0, '工具目录延迟装配的触发件数（默认 20；项目可用工具数超过它时，非核心工具不再逐条占用请求清单，改为按需检索；填 0 或负数=关闭该机制，全部工具常驻下发，修改即时生效）'),
    ('ai.tool.catalog-core-tools', '', '系统', 'string', 0, '始终常驻下发的核心工具名单（英文逗号分隔的工具名，默认为空=无核心常驻；名单内的工具即使触发目录延迟装配也会直接随请求提供，无需先检索）'),
    ('ai.tool.catalog-expand-max', '8', '系统', 'int', 0, '每次检索目录最多展开的工具数（默认 8；一次检索命中的工具超过上限时按目录顺序取前 8 个随下一轮请求下发，其余可再次检索获取，修改即时生效）')
ON CONFLICT (config_key) DO NOTHING;
