-- (AX batch): factory seed for the adaptive document-budget safety
-- factor. The extraction chain sizes its compression window per call as
--   routed-model catalog input limit x ai.doc.budget-safety-factor
-- (reader: ModelAdaptiveBudget in ai-service; base window falls back to 128000
-- tokens when the catalog row is unreadable and the endpoint-side
-- PromptSizeGuard clamp stays on top). The static ai.doc.token-budget key (V29)
-- remains seeded for the bridge consumers but no longer drives the extraction
-- decision plane.
-- Accepted interval 0.1~0.95: write-side check lives in core
-- ConfigServiceImpl.validateE8CompressionKey; read-side garbage values degrade
-- to this factory default instead of aborting an extraction job. Both faces are
-- edited together (single-source note in the reader javadoc).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('ai.doc.budget-safety-factor', '0.6', '系统', 'string', 0,
 '文档提取输入预算的自适应系数（允许 0.1~0.95，默认 0.6）：实际预算=所用模型的上下文上限×本系数，修改后下一份文档即时生效。系数调大更接近模型上限、摘要分组更少；调小更早分组摘要。填了无效值时自动按 0.6 执行，不影响提取；修改即时生效，无需重启')
ON CONFLICT (config_key) DO NOTHING;
