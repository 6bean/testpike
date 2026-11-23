-- segment B: per-group summary cache rows so a mid-round failure resumes
-- instead of re-burning the whole Map round. Rows with group_seq >= 1 hold ONE
-- summary block each (written as soon as that group is computed); the legacy
-- whole-round row keeps group_seq = 0 (list JSON). Both families coexist under
-- one unique key (document_id, gen_token, group_seq).
-- The switch also moves to its real consumer family: ai.doc.summary-cache.enabled
-- (the only consuming face is the extraction chain, the old ai.ctx.* home was a
-- key-family misplacement), and ships ON per the AI-feature-default-on rule.
ALTER TABLE tp_doc_summary_cache ADD COLUMN IF NOT EXISTS group_seq INT NOT NULL DEFAULT 0;
ALTER TABLE tp_doc_summary_cache DROP CONSTRAINT IF EXISTS uq_doc_summary_cache;
ALTER TABLE tp_doc_summary_cache ADD CONSTRAINT uq_doc_summary_cache UNIQUE (document_id, gen_token, group_seq);

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.doc.summary-cache.enabled', 'true', '系统', 'bool', 0, '文档分组摘要缓存开关（默认开启；同一提取轮次内已算好的分组摘要会被直接复用，重试不再重复调用模型生成摘要。值缺失或不合规时按开启处理并在日志点名本键。改为 false 保存即生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;

DELETE FROM tp_sys_config WHERE config_key = 'ai.ctx.doc-summaries-cache';
