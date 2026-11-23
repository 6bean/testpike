-- -family bookkeeping sweep (ticket #3): strip internal document codes
-- (V-number / T-number / ticket refs) from user-visible config descriptions.
-- Values, groups and ranges are untouched; rows already repaired by later
-- migrations stay out of scope via the IS DISTINCT FROM guard (replay-safe).
UPDATE tp_sys_config SET description = '摘要预算=组预算×比例（0.05~0.9）'
 WHERE config_key = 'ai.doc.summary-ratio'
   AND description IS DISTINCT FROM '摘要预算=组预算×比例（0.05~0.9）';
UPDATE tp_sys_config SET description = '摘要预算 token 下限（50~20000 且 ≤上限）'
 WHERE config_key = 'ai.doc.summary-min-tokens'
   AND description IS DISTINCT FROM '摘要预算 token 下限（50~20000 且 ≤上限）';
UPDATE tp_sys_config SET description = '摘要预算 token 上限（50~20000 且 ≥下限）'
 WHERE config_key = 'ai.doc.summary-max-tokens'
   AND description IS DISTINCT FROM '摘要预算 token 上限（50~20000 且 ≥下限）';
UPDATE tp_sys_config SET description = '逐级归并触发组数（2~64）'
 WHERE config_key = 'ai.doc.reduce-trigger-groups'
   AND description IS DISTINCT FROM '逐级归并触发组数（2~64）';
UPDATE tp_sys_config SET description = '归并层级上限（1~3）'
 WHERE config_key = 'ai.doc.reduce-max-levels'
   AND description IS DISTINCT FROM '归并层级上限（1~3）';
UPDATE tp_sys_config SET description = '探索单次最大步数上限（预算快照来源）'
 WHERE config_key = 'ai.explore.max-steps'
   AND description IS DISTINCT FROM '探索单次最大步数上限（预算快照来源）';
UPDATE tp_sys_config SET description = '探索访问页面上限（无进展/预算终止判据）'
 WHERE config_key = 'ai.explore.max-pages'
   AND description IS DISTINCT FROM '探索访问页面上限（无进展/预算终止判据）';
UPDATE tp_sys_config SET description = 'perf 档执行器租约 TTL 秒（默认 30 分钟，可配热更）'
 WHERE config_key = 'lease.perf-ttl-seconds'
   AND description IS DISTINCT FROM 'perf 档执行器租约 TTL 秒（默认 30 分钟，可配热更）';
UPDATE tp_sys_config SET description = '压测运行中熔断错误率阈值（比率 0-1，0.05=5%；场景 sla_json.breakerErrorRate 覆写优先）'
 WHERE config_key = 'perf.breaker-error-rate'
   AND description IS DISTINCT FROM '压测运行中熔断错误率阈值（比率 0-1，0.05=5%；场景 sla_json.breakerErrorRate 覆写优先）';
UPDATE tp_sys_config SET description = 'AI 批量生成单次上限（默认 500；域 1~500 写侧服务层校验，热更即时生效）'
 WHERE config_key = 'ai.batch.max-count'
   AND description IS DISTINCT FROM 'AI 批量生成单次上限（默认 500；域 1~500 写侧服务层校验，热更即时生效）';
