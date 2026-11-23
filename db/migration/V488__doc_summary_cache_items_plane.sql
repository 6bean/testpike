-- remediation ②: a second cache plane on tp_doc_summary_cache. The
-- grouped raw-extraction face reuses per-group REQUIREMENT
-- ITEMS exactly the way segment B reused per-group summaries: rows with
-- plane='items' (group_seq >= 1, item-list JSON) land as soon as one group
-- finishes, so a mid-run failure of the same generation skips the finished
-- groups instead of re-burning their model calls (the 17-group document
-- pattern). Existing rows keep plane='summary' (column default) and the
-- summary face stays byte-identical; the unique key gains the plane
-- dimension so one (document_id, gen_token, group_seq) holds one row per
-- plane side by side. The K8 switch description now states both uses.
ALTER TABLE tp_doc_summary_cache ADD COLUMN IF NOT EXISTS plane VARCHAR(32) NOT NULL DEFAULT 'summary';
ALTER TABLE tp_doc_summary_cache DROP CONSTRAINT IF EXISTS uq_doc_summary_cache;
ALTER TABLE tp_doc_summary_cache ADD CONSTRAINT uq_doc_summary_cache UNIQUE (document_id, gen_token, group_seq, plane);

UPDATE tp_sys_config SET description = '文档提取缓存开关（默认开启；同一提取轮次内重跑时，已算好的分组摘要与已提取的分组成果会被直接复用，不再重复调用模型。值缺失或不合规时按开启处理并在日志点名本键。改为 false 保存即生效，无需重启）'
WHERE config_key = 'ai.doc.summary-cache.enabled';
