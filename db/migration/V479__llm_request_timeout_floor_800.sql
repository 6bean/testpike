-- Floor every large-language-model request timeout default at 800s
-- (user order 2026-11-10): values below 800 rise to 800; anything already
-- higher or set manually to a bigger value stays untouched. Covers the
-- global call ceiling and the question-generation plane key on existing
-- databases; fresh installs replay this migration from the seed set, so the
-- shipped default converges to the same floor.
UPDATE tp_sys_config
   SET config_value = '800',
       description = 'LLM 调用超时秒数（默认 800，区间 10~900）'
 WHERE config_key = 'llm.timeout-seconds'
   AND config_value ~ '^[0-9]+$'
   AND config_value::int < 800;
UPDATE tp_sys_config
   SET config_value = '800',
       description = '出题生成链单次调用的超时上限（秒）。留空=沿用全局 llm.timeout-seconds；填写则只收紧出题生成链（分组造题）的单次调用。默认 800；允许 10~900；超界或不合规会被拒绝并提示原因。保存即生效，无需重启'
 WHERE config_key = 'ai.judge.qa-gen.llm-timeout-seconds'
   AND config_value ~ '^[0-9]+$'
   AND config_value::int < 800;
