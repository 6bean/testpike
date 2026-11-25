-- Factory hot keys for the bulk contract-enrichment execution leg (MJ P4):
--   * mock.spec-import.history-frame-cap -- how many process frames ONE
--     enrichment run keeps in the ledger before the sweeper evicts the oldest
--     (factory 2000, allowed 200~20000; read fresh every sweep round).
--   * mock.spec-import.enrich-batch-size -- endpoints per AI chat inside one
--     enrichment run (factory 20, allowed 1~100; bigger batch = fewer rounds).
--
-- Flyway is not wired in this project: apply manually to the target database,
-- then regenerate db/schema-init.sql with tools/ops/gen_schema_init.sh.

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('mock.spec-import.history-frame-cap', '2000', '接口仿真', 'int', 0,
     '一次批量完善任务保留的执行过程记录条数（默认 2000，超出上限的旧记录自动清除；修改即时生效）'),
    ('mock.spec-import.enrich-batch-size', '20', '接口仿真', 'int', 0,
     'AI 数据完善每次调用覆盖的接口数（默认 20；调大则分批更少、单次内容更多；修改即时生效）')
ON CONFLICT (config_key) DO NOTHING;
