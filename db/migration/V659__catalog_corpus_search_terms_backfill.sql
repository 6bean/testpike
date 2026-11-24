-- Corpus backfill for the catalog matcher (follows the V414 direct-assign shape).
--
-- Governance rows registered by later batches shipped with an empty synonym
-- column, so the catalog recall gate reported terms-empty 11 -> 28. This pass
-- fills every reachable row that still has no synonyms, including the 11 rows
-- the first CC batch pass left un-adjudicated. Words come from the on-screen
-- vocabulary already in the frontend locale (page names and section titles);
-- values are data (user vocabulary), comments stay English.
--
-- Guard: only rows whose synonym column is empty are rewritten, so a replay
-- never overwrites curated terms and a re-run is a no-op.
-- Flyway is not wired in this project: apply manually to the target database,
-- then regenerate db/schema-init.sql with tools/ops/gen_schema_init.sh.

UPDATE tp_ai_api_access_governance
   SET search_terms = 'AI批次,最新批次,生成批次,ai-batches,aiBatches,latest', updated_at = now()
 WHERE endpoint_key = 'GET /internal/ai-batches/latest' AND coalesce(search_terms, '') = '';
UPDATE tp_ai_api_access_governance
   SET search_terms = 'AI任务,单条生成任务,最新任务,ai-tasks,aiTasks,latest-single', updated_at = now()
 WHERE endpoint_key = 'GET /internal/ai-tasks/latest-single' AND coalesce(search_terms, '') = '';
UPDATE tp_ai_api_access_governance
   SET search_terms = 'AI任务,需求点在跑任务,生成进度,ai-tasks,aiTasks,live-by-requirements', updated_at = now()
 WHERE endpoint_key = 'GET /internal/ai-tasks/live-by-requirements' AND coalesce(search_terms, '') = '';
UPDATE tp_ai_api_access_governance
   SET search_terms = '接口变更分析,契约版本变更,影响面,api-spec-change-analyses,apiSpecChange', updated_at = now()
 WHERE endpoint_key = 'GET /internal/api-spec-change-analyses' AND coalesce(search_terms, '') = '';
UPDATE tp_ai_api_access_governance
   SET search_terms = '应用自动化,移动端元素,真机元素,appauto,app-auto,elements', updated_at = now()
 WHERE endpoint_key = 'GET /internal/appauto/elements' AND coalesce(search_terms, '') = '';
UPDATE tp_ai_api_access_governance
   SET search_terms = '集群,平台版本,组件版本,cluster,platform-version,platformVersion', updated_at = now()
 WHERE endpoint_key = 'GET /internal/cluster/platform-version' AND coalesce(search_terms, '') = '';
UPDATE tp_ai_api_access_governance
   SET search_terms = '集群,发布清单兼容性对比,升级适配,cluster,release-manifest,compat-diff', updated_at = now()
 WHERE endpoint_key = 'GET /internal/cluster/release-manifest/compat-diff' AND coalesce(search_terms, '') = '';
UPDATE tp_ai_api_access_governance
   SET search_terms = '集群,发布清单跨版本兼容对比,升级适配,cluster,release-manifest,compat-diff-with', updated_at = now()
 WHERE endpoint_key = 'GET /internal/cluster/release-manifest/compat-diff-with' AND coalesce(search_terms, '') = '';
UPDATE tp_ai_api_access_governance
   SET search_terms = '集群,最新发布清单,版本基线,cluster,release-manifest,latest', updated_at = now()
 WHERE endpoint_key = 'GET /internal/cluster/release-manifest/latest' AND coalesce(search_terms, '') = '';
UPDATE tp_ai_api_access_governance
   SET search_terms = '集群,发布清单列表,升级台账,cluster,release-manifest,list', updated_at = now()
 WHERE endpoint_key = 'GET /internal/cluster/release-manifest/list' AND coalesce(search_terms, '') = '';
UPDATE tp_ai_api_access_governance
   SET search_terms = '缺陷库,集成缺陷,缺陷闭环,defect-library,defects', updated_at = now()
 WHERE endpoint_key = 'GET /internal/defect-library' AND coalesce(search_terms, '') = '';
UPDATE tp_ai_api_access_governance
   SET search_terms = '文档变更分析,章节变更,重传对比,doc-change-analyses,docChange', updated_at = now()
 WHERE endpoint_key = 'GET /internal/doc-change-analyses' AND coalesce(search_terms, '') = '';
UPDATE tp_ai_api_access_governance
   SET search_terms = '页面写入画像,写操作页面,探查写入记录,page-write-profiles,write-profiles', updated_at = now()
 WHERE endpoint_key = 'GET /internal/page-write-profiles/page' AND coalesce(search_terms, '') = '';
UPDATE tp_ai_api_access_governance
   SET search_terms = '页面写入画像,风险页面,写风险清单,page-write-profiles,risk-pages', updated_at = now()
 WHERE endpoint_key = 'GET /internal/page-write-profiles/risk-pages' AND coalesce(search_terms, '') = '';
UPDATE tp_ai_api_access_governance
   SET search_terms = '项目记忆,记忆条目,上下文沉淀,project-memory,projectMemory', updated_at = now()
 WHERE endpoint_key = 'GET /internal/project-memory' AND coalesce(search_terms, '') = '';
UPDATE tp_ai_api_access_governance
   SET search_terms = '脚本修复,修复草案,失败脚本,script-repairs,scriptRepairs', updated_at = now()
 WHERE endpoint_key = 'GET /internal/script-repairs' AND coalesce(search_terms, '') = '';
UPDATE tp_ai_api_access_governance
   SET search_terms = '登录态账本,已登录身份,会话状态,session-states,sessionStates,login-state', updated_at = now()
 WHERE endpoint_key = 'GET /internal/session-states' AND coalesce(search_terms, '') = '';
UPDATE tp_ai_api_access_governance
   SET search_terms = '登录态账本,登录态分页,session-states,sessionStates,page', updated_at = now()
 WHERE endpoint_key = 'GET /internal/session-states/page' AND coalesce(search_terms, '') = '';
UPDATE tp_ai_api_access_governance
   SET search_terms = '登录态解析,按名称定位登录态,session-states,resolve', updated_at = now()
 WHERE endpoint_key = 'GET /internal/session-states/resolve' AND coalesce(search_terms, '') = '';
UPDATE tp_ai_api_access_governance
   SET search_terms = '站点档案,站点画像,被测站点,site-profiles,siteProfiles', updated_at = now()
 WHERE endpoint_key = 'GET /internal/site-profiles' AND coalesce(search_terms, '') = '';
UPDATE tp_ai_api_access_governance
   SET search_terms = '整站测绘,测绘记录,站点扫描,site-survey-runs,siteSurvey', updated_at = now()
 WHERE endpoint_key = 'GET /internal/site-survey-runs' AND coalesce(search_terms, '') = '';
UPDATE tp_ai_api_access_governance
   SET search_terms = '整站测绘,差异提案,元素变更提案,diff-proposal,site-survey-runs', updated_at = now()
 WHERE endpoint_key = 'GET /internal/site-survey-runs/{runId}/diff-proposal' AND coalesce(search_terms, '') = '';
UPDATE tp_ai_api_access_governance
   SET search_terms = '视觉基线,基线截图,截图比对,visual-baselines,visualBaseline', updated_at = now()
 WHERE endpoint_key = 'GET /internal/visual-baselines' AND coalesce(search_terms, '') = '';
UPDATE tp_ai_api_access_governance
   SET search_terms = '页面元素库,定位锚,元素清单,web-elements,webElement,locator', updated_at = now()
 WHERE endpoint_key = 'GET /internal/web-elements' AND coalesce(search_terms, '') = '';
UPDATE tp_ai_api_access_governance
   SET search_terms = '页面元素库,锚点健康度,定位可用性,web-elements,health', updated_at = now()
 WHERE endpoint_key = 'GET /internal/web-elements/health' AND coalesce(search_terms, '') = '';
UPDATE tp_ai_api_access_governance
   SET search_terms = '页面元素库,已建档页面,页面清单,web-elements,pages', updated_at = now()
 WHERE endpoint_key = 'GET /internal/web-elements/pages' AND coalesce(search_terms, '') = '';
UPDATE tp_ai_api_access_governance
   SET search_terms = '页面元素库,定位提示词摘要,prompt-summary,web-elements', updated_at = now()
 WHERE endpoint_key = 'GET /internal/web-elements/prompt-summary' AND coalesce(search_terms, '') = '';
UPDATE tp_ai_api_access_governance
   SET search_terms = '页面元素库,改版风暴,定位失效集中期,storms,web-elements', updated_at = now()
 WHERE endpoint_key = 'GET /internal/web-elements/storms' AND coalesce(search_terms, '') = '';
