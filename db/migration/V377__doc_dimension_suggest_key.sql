-- V377: extraction-preview dimension suggestions — the advisory half of the
-- requirement-list quality loop. A per-document switch (factory default on,
-- hot-reloaded) gates the server-computed "missing review dimensions" set
-- (exception-flow / boundary / non-functional minus what the extracted items
-- already present) echoed on GET /ai/documents/{id}/extraction. Suggestions
-- are advice only: the checkbox-confirm and review human gates are unchanged,
-- and a closed switch hides the advice without any extra upstream call.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.doc.dimension-suggest', 'true', '系统', 'bool', 0,
     '需求点提取预览的维度建议开关（默认开启；开启时预览返回异常流/边界/非功能三类中尚未被任何条目覆盖的维度建议，点击建议可触发定向补提；关闭时预览不再返回建议，提取与入库流程不受影响。修改即时生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;
