-- CC batch : widen the catalog search corpus with words shipped in the
-- frontend locale (zh-CN.ts), fixing the misses the first recall-gate run
-- named (77.78% -> target >= floor 90). Appends are guarded so a replay never
-- duplicates a term. Values are data (user vocabulary), comments stay English.
UPDATE tp_ai_api_access_governance SET search_terms = search_terms || ',我的项目'
 WHERE endpoint_key = 'GET /internal/user-projects' AND search_terms NOT LIKE '%我的项目%';
UPDATE tp_ai_api_access_governance SET search_terms = search_terms || ',命中次数'
 WHERE endpoint_key = 'GET /internal/mock/hit/count' AND search_terms NOT LIKE '%命中次数%';
UPDATE tp_ai_api_access_governance SET search_terms = search_terms || ',挡板'
 WHERE endpoint_key = 'GET /internal/mock/stub/list' AND search_terms NOT LIKE '%挡板%';
UPDATE tp_ai_api_access_governance SET search_terms = search_terms || ',数据工厂'
 WHERE endpoint_key = 'GET /internal/data-factories' AND search_terms NOT LIKE '%数据工厂%';
UPDATE tp_ai_api_access_governance SET search_terms = search_terms || ',技能市场'
 WHERE endpoint_key = 'GET /internal/skills/market' AND search_terms NOT LIKE '%技能市场%';
UPDATE tp_ai_api_access_governance SET search_terms = search_terms || ',技能市场'
 WHERE endpoint_key = 'GET /internal/skills' AND search_terms NOT LIKE '%技能市场%';
UPDATE tp_ai_api_access_governance SET search_terms = search_terms || ',压测场景'
 WHERE endpoint_key = 'GET /internal/perf/scenarios' AND search_terms NOT LIKE '%压测场景%';
