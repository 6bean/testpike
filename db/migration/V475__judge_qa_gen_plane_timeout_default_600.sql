-- Raise the question-generation plane call ceiling default from 120s to 600s
-- (user order 2026-11-09): long grouped question generation needs a wider per-call
-- window. The reader bounds move to 10~900 in the same release; fresh installs get
-- 600 straight from schema-init, this migration updates existing databases and only
-- rewrites the legacy 120 so manual overrides stay untouched.
UPDATE tp_sys_config
   SET config_value = '600',
       description = '出题生成链单次调用的超时上限（秒）。留空=沿用全局 llm.timeout-seconds；填写则只收紧出题生成链（分组造题）的单次调用。允许 10~900；超界或不合规会被拒绝并提示原因。保存即生效，无需重启'
 WHERE config_key = 'ai.judge.qa-gen.llm-timeout-seconds'
   AND config_value = '120';
-- The same release widens the shared save-side window to 10~900, so the two
-- sibling descriptions stay truthful on existing databases (values untouched:
-- global keeps its own value, the extraction plane keeps blank = global face).
UPDATE tp_sys_config
   SET description = '需求点提取链单次调用的超时上限（秒）。留空=沿用全局 llm.timeout-seconds；填写则只收紧提取链（最终提取/分组摘要/缺口追问）的单次调用，与任务总时限取更小值。允许 10~900；超界或不合规会被拒绝并提示原因。保存即生效，无需重启'
 WHERE config_key = 'ai.doc.llm-timeout-seconds';

UPDATE tp_sys_config
   SET description = 'LLM 调用超时秒数（默认 300，区间 10~900）'
 WHERE config_key = 'llm.timeout-seconds';
