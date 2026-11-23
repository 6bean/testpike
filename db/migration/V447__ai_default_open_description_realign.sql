-- Migration: realign shipped AI switch descriptions with their default-open values.
--
-- Why this file exists:
--   The default-open batch raised the factory values of the AI feature
--   switches to "on" but several tp_sys_config.description literals still
--   advertised the old factory-off state, and a small number of databases may
--   still hold the pre-batch value. Operators read these descriptions on the
--   settings page, so a stale claim is wrong operational information: the
--   text must state the shipped default, what the switch does, and what
--   happens when it is off. Idempotent: every statement only touches rows
--   whose value/description actually drifted, so re-running changes nothing.
UPDATE tp_sys_config
SET description = '对话启动回归演练批次的开关（默认开启；启动仍需本项目在编号列表内，且每次启动都要人工点击确认才会执行；关闭后对话不再发起演练批次；在「系统设置」中修改）'
WHERE config_key = 'ai.chat.rehearsal-run.enabled'
  AND description IS DISTINCT FROM '对话启动回归演练批次的开关（默认开启；启动仍需本项目在编号列表内，且每次启动都要人工点击确认才会执行；关闭后对话不再发起演练批次；在「系统设置」中修改）';
UPDATE tp_sys_config
SET description = '文档分段摘要缓存开关（默认开启；同一文档重新提取时复用已生成的分段摘要，不再重复逐段归纳；文档内容更新后缓存自动失效；关闭后每次提取重新逐段归纳）'
WHERE config_key = 'ai.ctx.doc-summaries-cache'
  AND description IS DISTINCT FROM '文档分段摘要缓存开关（默认开启；同一文档重新提取时复用已生成的分段摘要，不再重复逐段归纳；文档内容更新后缓存自动失效；关闭后每次提取重新逐段归纳）';
UPDATE tp_sys_config
SET description = '失败归因主开关（默认开启；关闭后失败不再调用模型归因、也不写入归因结果；仅支持 0/1，在本页打开即时生效）'
WHERE config_key = 'ai.classify.enabled'
  AND description IS DISTINCT FROM '失败归因主开关（默认开启；关闭后失败不再调用模型归因、也不写入归因结果；仅支持 0/1，在本页打开即时生效）';
UPDATE tp_sys_config
SET description = '失败自动修复主开关（默认开启；关闭后不再产生修复提案，也不改动脚本版本；仅支持 0/1，在本页打开即时生效）'
WHERE config_key = 'ai.repair.enabled'
  AND description IS DISTINCT FROM '失败自动修复主开关（默认开启；关闭后不再产生修复提案，也不改动脚本版本；仅支持 0/1，在本页打开即时生效）';
-- Heal any database that still carries the pre-batch factory-off value.
UPDATE tp_sys_config
SET config_value = 'true'
WHERE config_key = 'ai.chat.rehearsal-run.enabled'
  AND config_value IN ('0', 'false');
UPDATE tp_sys_config
SET config_value = 'true'
WHERE config_key = 'ai.ctx.doc-summaries-cache'
  AND config_value IN ('0', 'false');
UPDATE tp_sys_config
SET config_value = '1'
WHERE config_key = 'ai.classify.enabled'
  AND config_value IN ('0', 'false');
UPDATE tp_sys_config
SET config_value = '1'
WHERE config_key = 'ai.repair.enabled'
  AND config_value IN ('0', 'false');
-- The table comment in the cache-creation migration claimed the switch ships
-- off; the dictionary on live databases is corrected here and the source file
-- carries the fixed text for fresh installs.
COMMENT ON TABLE tp_doc_summary_cache IS
    'Generation-keyed cache of Map/Reduce group summaries for document extraction (AP-1 side (3); switch ai.ctx.doc-summaries-cache, default on)';
