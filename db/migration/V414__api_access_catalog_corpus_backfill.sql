-- Corpus backfill for the catalog matcher (CC batch, ).
--
-- Rewrites ai_desc to verb + named object and registers curated synonyms
-- for every reachable governance row. Every word is taken from the menu
-- labels and locale values already on disk (see tools/catalog_corpus_build.py
-- and docs/change-eval/t557_corpus_ledger.json for the per-row provenance).
--
-- Flyway is not wired in this project: apply manually to the target database,
-- then regenerate db/schema-init.sql with tools/ops/gen_schema_init.sh.

UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询接口清单的覆盖矩阵数据（项目由会话自动带入，无需手填）', search_terms = '接口清单,覆盖矩阵,api-inventory,apiInventory,coverage', updated_at = now()
 WHERE endpoint_key = 'GET /internal/api-inventory/coverage' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询接口清单的仿真端点总表数据（项目由会话自动带入，无需手填）', search_terms = '接口清单,仿真端点总表,api-inventory,apiInventory,endpoints', updated_at = now()
 WHERE endpoint_key = 'GET /internal/api-inventory/endpoints' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询提供方契约数据（项目由会话自动带入，无需手填）', search_terms = '提供方契约,apiSpecSubscriptions,api-spec-subscriptions', updated_at = now()
 WHERE endpoint_key = 'GET /internal/api-spec-subscriptions' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询契约数据（项目由会话自动带入，无需手填）', search_terms = '契约,apiSpecs,api-specs', updated_at = now()
 WHERE endpoint_key = 'GET /internal/api-specs' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询契约的历史数据（项目由会话自动带入，无需手填）', search_terms = '契约,历史,apiSpecs,api-specs,history', updated_at = now()
 WHERE endpoint_key = 'GET /internal/api-specs/history' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询接口测试的接口集数据（项目由会话自动带入，无需手填）', search_terms = '接口测试,接口集,apitest,collections', updated_at = now()
 WHERE endpoint_key = 'GET /internal/apitest/collections' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看接口测试的接口集数据（项目由会话自动带入，无需手填）', search_terms = '接口测试,接口集,apitest,collections', updated_at = now()
 WHERE endpoint_key = 'GET /internal/apitest/collections/{id}' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询接口测试数据（项目由会话自动带入，无需手填）', search_terms = '接口测试,apitest,flowTemplates,flow-templates', updated_at = now()
 WHERE endpoint_key = 'GET /internal/apitest/flow-templates' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看接口测试数据（项目由会话自动带入，无需手填）', search_terms = '接口测试,apitest,flowTemplates,flow-templates', updated_at = now()
 WHERE endpoint_key = 'GET /internal/apitest/flow-templates/{id}' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询接口测试的请求数数据（项目由会话自动带入，无需手填）', search_terms = '接口测试,请求数,apitest,requests', updated_at = now()
 WHERE endpoint_key = 'GET /internal/apitest/requests' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询接口测试的签名预设数据（项目由会话自动带入，无需手填）', search_terms = '接口测试,签名预设,请求数,apitest,requests,authPresets,auth-presets', updated_at = now()
 WHERE endpoint_key = 'GET /internal/apitest/requests/auth-presets' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询接口测试的请求数数据（项目由会话自动带入，无需手填）', search_terms = '接口测试,请求数,apitest,requests,effectiveBatch,effective-batch', updated_at = now()
 WHERE endpoint_key = 'GET /internal/apitest/requests/effective-batch' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看接口测试的请求数数据（项目由会话自动带入，无需手填）', search_terms = '接口测试,请求数,apitest,requests', updated_at = now()
 WHERE endpoint_key = 'GET /internal/apitest/requests/{id}' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看接口测试的生效版本数据（项目由会话自动带入，无需手填）', search_terms = '接口测试,生效版本,请求数,apitest,requests,effective', updated_at = now()
 WHERE endpoint_key = 'GET /internal/apitest/requests/{id}/effective' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询接口测试数据（项目由会话自动带入，无需手填）', search_terms = '接口测试,apitest,runSets,run-sets', updated_at = now()
 WHERE endpoint_key = 'GET /internal/apitest/run-sets' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询接口测试的预览数据（项目由会话自动带入，无需手填）', search_terms = '接口测试,预览,apitest,runSets,run-sets,previews', updated_at = now()
 WHERE endpoint_key = 'GET /internal/apitest/run-sets/previews' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看接口测试数据（项目由会话自动带入，无需手填）', search_terms = '接口测试,apitest,runSets,run-sets', updated_at = now()
 WHERE endpoint_key = 'GET /internal/apitest/run-sets/{id}' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询接口测试的执行数据（项目由会话自动带入，无需手填）', search_terms = '接口测试,执行,apitest,runs', updated_at = now()
 WHERE endpoint_key = 'GET /internal/apitest/runs' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看接口测试的执行数据（项目由会话自动带入，无需手填）', search_terms = '接口测试,执行,apitest,runs', updated_at = now()
 WHERE endpoint_key = 'GET /internal/apitest/runs/{id}' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询接口测试数据（项目由会话自动带入，无需手填）', search_terms = '接口测试,apitest,step-aliases,stepAliases', updated_at = now()
 WHERE endpoint_key = 'GET /internal/apitest/step-aliases' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看接口测试数据（项目由会话自动带入，无需手填）', search_terms = '接口测试,apitest,step-aliases,stepAliases', updated_at = now()
 WHERE endpoint_key = 'GET /internal/apitest/step-aliases/{id}' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询App 自动化的训练样本台账数据（项目由会话自动带入，无需手填）', search_terms = 'App 自动化,训练样本台账,appauto,ai-samples,aiSamples', updated_at = now()
 WHERE endpoint_key = 'GET /internal/appauto/ai-samples' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询App 自动化的命中率数据（项目由会话自动带入，无需手填）', search_terms = 'App 自动化,命中率,训练样本台账,appauto,ai-samples,aiSamples,hitRate,hit-rate', updated_at = now()
 WHERE endpoint_key = 'GET /internal/appauto/ai-samples/hit-rate' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询App 自动化数据（项目由会话自动带入，无需手填）', search_terms = 'App 自动化,appauto,apps', updated_at = now()
 WHERE endpoint_key = 'GET /internal/appauto/apps' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看App 自动化数据（项目由会话自动带入，无需手填）', search_terms = 'App 自动化,appauto,apps', updated_at = now()
 WHERE endpoint_key = 'GET /internal/appauto/apps/{id}' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询App 自动化的设备数据（项目由会话自动带入，无需手填）', search_terms = 'App 自动化,设备,appauto,devices', updated_at = now()
 WHERE endpoint_key = 'GET /internal/appauto/devices' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看App 自动化的设备数据（项目由会话自动带入，无需手填）', search_terms = 'App 自动化,设备,appauto,devices', updated_at = now()
 WHERE endpoint_key = 'GET /internal/appauto/devices/{id}' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看App 自动化的占用中数据（项目由会话自动带入，无需手填）', search_terms = 'App 自动化,占用中,设备,appauto,devices,lease-runs,leaseRuns', updated_at = now()
 WHERE endpoint_key = 'GET /internal/appauto/devices/{id}/lease-runs' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询App 自动化的项目数据（项目由会话自动带入，无需手填）', search_terms = 'App 自动化,项目,appauto,engine-support,engineSupport', updated_at = now()
 WHERE endpoint_key = 'GET /internal/appauto/engine-support' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询App 自动化的压测引擎数据（项目由会话自动带入，无需手填）', search_terms = 'App 自动化,压测引擎,appauto,engines', updated_at = now()
 WHERE endpoint_key = 'GET /internal/appauto/engines' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询App 自动化的压测引擎数据（项目由会话自动带入，无需手填）', search_terms = 'App 自动化,压测引擎,appauto,engines,availability', updated_at = now()
 WHERE endpoint_key = 'GET /internal/appauto/engines/availability' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询App 自动化的调度健康卡数据（项目由会话自动带入，无需手填）', search_terms = 'App 自动化,调度健康卡,压测引擎,appauto,engines,health', updated_at = now()
 WHERE endpoint_key = 'GET /internal/appauto/engines/health' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看App 自动化的压测引擎数据（项目由会话自动带入，无需手填）', search_terms = 'App 自动化,压测引擎,appauto,engines', updated_at = now()
 WHERE endpoint_key = 'GET /internal/appauto/engines/{code}' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询App 自动化的个人中心数据（项目由会话自动带入，无需手填）', search_terms = 'App 自动化,个人中心,appauto,env-profiles,envProfiles', updated_at = now()
 WHERE endpoint_key = 'GET /internal/appauto/env-profiles' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看App 自动化的个人中心数据（项目由会话自动带入，无需手填）', search_terms = 'App 自动化,个人中心,appauto,env-profiles,envProfiles', updated_at = now()
 WHERE endpoint_key = 'GET /internal/appauto/env-profiles/{id}' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询App 自动化的机型数据（项目由会话自动带入，无需手填）', search_terms = 'App 自动化,机型,appauto,matrix-model-report,matrixModelReport', updated_at = now()
 WHERE endpoint_key = 'GET /internal/appauto/matrix-model-report' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询App 自动化的平台数据（项目由会话自动带入，无需手填）', search_terms = 'App 自动化,平台,appauto,platforms', updated_at = now()
 WHERE endpoint_key = 'GET /internal/appauto/platforms' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看App 自动化的平台数据（项目由会话自动带入，无需手填）', search_terms = 'App 自动化,平台,appauto,platforms', updated_at = now()
 WHERE endpoint_key = 'GET /internal/appauto/platforms/{code}' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询App 自动化的弹窗规则数据（项目由会话自动带入，无需手填）', search_terms = 'App 自动化,弹窗规则,appauto,popupRules,popup-rules', updated_at = now()
 WHERE endpoint_key = 'GET /internal/appauto/popup-rules' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看App 自动化的弹窗规则数据（项目由会话自动带入，无需手填）', search_terms = 'App 自动化,弹窗规则,appauto,popupRules,popup-rules', updated_at = now()
 WHERE endpoint_key = 'GET /internal/appauto/popup-rules/{id}' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询App 自动化数据（项目由会话自动带入，无需手填）', search_terms = 'App 自动化,appauto,portability', updated_at = now()
 WHERE endpoint_key = 'GET /internal/appauto/portability' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看App 自动化的建于数据（项目由会话自动带入，无需手填）', search_terms = 'App 自动化,建于,appauto,record-drafts,recordDrafts', updated_at = now()
 WHERE endpoint_key = 'GET /internal/appauto/record-drafts/{id}' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询App 自动化的设备预约日历数据（项目由会话自动带入，无需手填）', search_terms = 'App 自动化,设备预约日历,appauto,reservation', updated_at = now()
 WHERE endpoint_key = 'GET /internal/appauto/reservation' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看App 自动化的设备预约日历数据（项目由会话自动带入，无需手填）', search_terms = 'App 自动化,设备预约日历,appauto,reservation', updated_at = now()
 WHERE endpoint_key = 'GET /internal/appauto/reservation/{id}' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询App 自动化数据（项目由会话自动带入，无需手填）', search_terms = 'App 自动化,appauto,stabilityRows,stability-rows', updated_at = now()
 WHERE endpoint_key = 'GET /internal/appauto/stability-rows' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询App 自动化数据（项目由会话自动带入，无需手填）', search_terms = 'App 自动化,appauto,stepSuggestions,step-suggestions', updated_at = now()
 WHERE endpoint_key = 'GET /internal/appauto/step-suggestions' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看App 自动化数据（项目由会话自动带入，无需手填）', search_terms = 'App 自动化,appauto,stepSuggestions,step-suggestions', updated_at = now()
 WHERE endpoint_key = 'GET /internal/appauto/step-suggestions/{id}' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看App 自动化的驳回数据（项目由会话自动带入，无需手填）', search_terms = 'App 自动化,驳回,appauto,takeover', updated_at = now()
 WHERE endpoint_key = 'GET /internal/appauto/takeover/{id}' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询App 自动化数据（项目由会话自动带入，无需手填）', search_terms = 'App 自动化,appauto,upgrade-flaky-links,upgradeFlakyLinks', updated_at = now()
 WHERE endpoint_key = 'GET /internal/appauto/upgrade-flaky-links' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询App 自动化的推荐数据（项目由会话自动带入，无需手填）', search_terms = 'App 自动化,推荐,appauto,upgradeWindow,upgrade-window', updated_at = now()
 WHERE endpoint_key = 'GET /internal/appauto/upgrade-window' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询审计日志数据（项目由会话自动带入，无需手填）', search_terms = '审计日志,auditLogs,audit-logs', updated_at = now()
 WHERE endpoint_key = 'GET /internal/audit-logs' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询审计日志数据（项目由会话自动带入，无需手填）', search_terms = '审计日志,auditLogs,audit-logs,cursor', updated_at = now()
 WHERE endpoint_key = 'GET /internal/audit-logs/cursor' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询执行批次数据（项目由会话自动带入，无需手填）', search_terms = '执行批次,batches', updated_at = now()
 WHERE endpoint_key = 'GET /internal/batches' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看执行批次的失败数据（项目由会话自动带入，无需手填）', search_terms = '执行批次,失败,batches,badge', updated_at = now()
 WHERE endpoint_key = 'GET /internal/batches/badge/{badgeToken}' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询执行批次的模板数据（项目由会话自动带入，无需手填）', search_terms = '执行批次,模板,batches,templates', updated_at = now()
 WHERE endpoint_key = 'GET /internal/batches/templates' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看执行批次的模板数据（项目由会话自动带入，无需手填）', search_terms = '执行批次,模板,batches,templates', updated_at = now()
 WHERE endpoint_key = 'GET /internal/batches/templates/{templateId}' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看执行批次数据（项目由会话自动带入，无需手填）', search_terms = '执行批次,batches', updated_at = now()
 WHERE endpoint_key = 'GET /internal/batches/{id}' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看执行批次的失败数据（项目由会话自动带入，无需手填）', search_terms = '执行批次,失败,batches,badge', updated_at = now()
 WHERE endpoint_key = 'GET /internal/batches/{id}/badge' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看执行批次的用例数据（项目由会话自动带入，无需手填）', search_terms = '执行批次,用例,batches,cases', updated_at = now()
 WHERE endpoint_key = 'GET /internal/batches/{id}/cases' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看执行批次的其中数据（项目由会话自动带入，无需手填）', search_terms = '执行批次,其中,batches,insight', updated_at = now()
 WHERE endpoint_key = 'GET /internal/batches/{id}/insight' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看执行批次的统计报表数据（项目由会话自动带入，无需手填）', search_terms = '执行批次,统计报表,batches,report', updated_at = now()
 WHERE endpoint_key = 'GET /internal/batches/{id}/report' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询用例数据（项目由会话自动带入，无需手填）', search_terms = '用例,cases', updated_at = now()
 WHERE endpoint_key = 'GET /internal/cases' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询用例的收起数据（项目由会话自动带入，无需手填）', search_terms = '用例,收起,cases,flakyProposals,flaky-proposals', updated_at = now()
 WHERE endpoint_key = 'GET /internal/cases/flaky-proposals' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询用例数据（项目由会话自动带入，无需手填）', search_terms = '用例,cases,flow-guide,flowGuide', updated_at = now()
 WHERE endpoint_key = 'GET /internal/cases/flow-guide' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询用例的回收站数据（项目由会话自动带入，无需手填）', search_terms = '用例,回收站,cases,recycle-bin,recycleBin', updated_at = now()
 WHERE endpoint_key = 'GET /internal/cases/recycle-bin' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看用例的需求数据（项目由会话自动带入，无需手填）', search_terms = '用例,需求,cases,requirements', updated_at = now()
 WHERE endpoint_key = 'GET /internal/cases/{caseId}/requirements' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看用例的追溯链数据（项目由会话自动带入，无需手填）', search_terms = '用例,追溯链,cases,trace-chain,traceChain', updated_at = now()
 WHERE endpoint_key = 'GET /internal/cases/{caseId}/trace-chain' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看用例数据（项目由会话自动带入，无需手填）', search_terms = '用例,cases', updated_at = now()
 WHERE endpoint_key = 'GET /internal/cases/{id}' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看用例的参考文档数据（项目由会话自动带入，无需手填）', search_terms = '用例,参考文档,cases,references', updated_at = now()
 WHERE endpoint_key = 'GET /internal/cases/{id}/references' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看用例的评审工作台数据（项目由会话自动带入，无需手填）', search_terms = '用例,评审工作台,cases,review-logs,reviewLogs', updated_at = now()
 WHERE endpoint_key = 'GET /internal/cases/{id}/review-logs' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看用例的版本数据（项目由会话自动带入，无需手填）', search_terms = '用例,版本,cases,versions', updated_at = now()
 WHERE endpoint_key = 'GET /internal/cases/{id}/versions' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看用例的差异数据（项目由会话自动带入，无需手填）', search_terms = '用例,差异,版本,cases,versions,diff', updated_at = now()
 WHERE endpoint_key = 'GET /internal/cases/{id}/versions/{v}/diff' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询所属项目数据（项目由会话自动带入，无需手填）', search_terms = '所属项目,cluster,components', updated_at = now()
 WHERE endpoint_key = 'GET /internal/cluster/components' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询发现新版本待数据（项目由会话自动带入，无需手填）', search_terms = '发现新版本待,cluster,release-candidate,releaseCandidate,list', updated_at = now()
 WHERE endpoint_key = 'GET /internal/cluster/release-candidate/list' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询发现新版本待数据（项目由会话自动带入，无需手填）', search_terms = '发现新版本待,cluster,release-candidate,releaseCandidate,pending-count,pendingCount', updated_at = now()
 WHERE endpoint_key = 'GET /internal/cluster/release-candidate/pending-count' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询升级计划数据（项目由会话自动带入，无需手填）', search_terms = '升级计划,cluster,upgradePlans,upgrade-plans', updated_at = now()
 WHERE endpoint_key = 'GET /internal/cluster/upgrade-plans' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看升级计划数据（项目由会话自动带入，无需手填）', search_terms = '升级计划,cluster,upgradePlans,upgrade-plans', updated_at = now()
 WHERE endpoint_key = 'GET /internal/cluster/upgrade-plans/{id}' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看步骤数据（项目由会话自动带入，无需手填）', search_terms = '步骤,升级计划,cluster,upgradePlans,upgrade-plans,steps', updated_at = now()
 WHERE endpoint_key = 'GET /internal/cluster/upgrade-plans/{id}/steps' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询评论数据（项目由会话自动带入，无需手填）', search_terms = '评论,comments,unreadCount,unread-count', updated_at = now()
 WHERE endpoint_key = 'GET /internal/comments/unread-count' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看调度健康卡的上下文已用数据（项目由会话自动带入，无需手填）', search_terms = '调度健康卡,上下文已用,成本中心,cost,budget', updated_at = now()
 WHERE endpoint_key = 'GET /internal/cost/budget/{projectId}' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询调度健康卡的成本中心数据（项目由会话自动带入，无需手填）', search_terms = '调度健康卡,成本中心,cost,overview', updated_at = now()
 WHERE endpoint_key = 'GET /internal/cost/overview' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询工作台数据（项目由会话自动带入，无需手填）', search_terms = '工作台,dashboard,overview', updated_at = now()
 WHERE endpoint_key = 'GET /internal/dashboard/overview' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询自动造数数据（项目由会话自动带入，无需手填）', search_terms = '自动造数,data-factories,dataFactories', updated_at = now()
 WHERE endpoint_key = 'GET /internal/data-factories' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询自动造数的键名数据（项目由会话自动带入，无需手填）', search_terms = '自动造数,键名,data-factories,dataFactories,pool-items,poolItems', updated_at = now()
 WHERE endpoint_key = 'GET /internal/data-factories/pool-items' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看自动造数的最近访问数据（项目由会话自动带入，无需手填）', search_terms = '自动造数,最近访问,data-factories,dataFactories,recent-runs,recentRuns', updated_at = now()
 WHERE endpoint_key = 'GET /internal/data-factories/{id}/recent-runs' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看自动造数的执行数据（项目由会话自动带入，无需手填）', search_terms = '自动造数,执行,data-factories,dataFactories,runs', updated_at = now()
 WHERE endpoint_key = 'GET /internal/data-factories/{id}/runs' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询删除审批数据（项目由会话自动带入，无需手填）', search_terms = '删除审批,delete-requests,deleteRequests', updated_at = now()
 WHERE endpoint_key = 'GET /internal/delete-requests' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询上传需求文档数据（项目由会话自动带入，无需手填）', search_terms = '上传需求文档,documents', updated_at = now()
 WHERE endpoint_key = 'GET /internal/documents' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询执行器数据（项目由会话自动带入，无需手填）', search_terms = '执行器,executors', updated_at = now()
 WHERE endpoint_key = 'GET /internal/executors' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看执行器的历史数据（项目由会话自动带入，无需手填）', search_terms = '执行器,历史,用例,executors,cases,history', updated_at = now()
 WHERE endpoint_key = 'GET /internal/executors/cases/{caseId}/history' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询执行器数据（项目由会话自动带入，无需手填）', search_terms = '执行器,executors,parallel-water-level,parallelWaterLevel', updated_at = now()
 WHERE endpoint_key = 'GET /internal/executors/parallel-water-level' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询执行器的资源池数据（项目由会话自动带入，无需手填）', search_terms = '执行器,资源池,executors,pools', updated_at = now()
 WHERE endpoint_key = 'GET /internal/executors/pools' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询执行器的任务数据（项目由会话自动带入，无需手填）', search_terms = '执行器,任务,executors,tasks', updated_at = now()
 WHERE endpoint_key = 'GET /internal/executors/tasks' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询执行器的在跑数据（项目由会话自动带入，无需手填）', search_terms = '执行器,在跑,任务,executors,tasks,running', updated_at = now()
 WHERE endpoint_key = 'GET /internal/executors/tasks/running' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看执行器的自检报告数据（项目由会话自动带入，无需手填）', search_terms = '执行器,自检报告,executors,self-check,selfCheck', updated_at = now()
 WHERE endpoint_key = 'GET /internal/executors/{executorId}/self-check' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询自主探索的会话数据（项目由会话自动带入，无需手填）', search_terms = '自主探索,会话,explore,sessions', updated_at = now()
 WHERE endpoint_key = 'GET /internal/explore/sessions' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询收藏数据（项目由会话自动带入，无需手填）', search_terms = '收藏,favorites', updated_at = now()
 WHERE endpoint_key = 'GET /internal/favorites' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询知识库数据（项目由会话自动带入，无需手填）', search_terms = '知识库,knowledge,docs', updated_at = now()
 WHERE endpoint_key = 'GET /internal/knowledge/docs' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询同步订阅数据（项目由会话自动带入，无需手填）', search_terms = '同步订阅,mcpKnowledgeSyncs,mcp-knowledge-syncs', updated_at = now()
 WHERE endpoint_key = 'GET /internal/mcp-knowledge-syncs' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询MCP 服务数据（项目由会话自动带入，无需手填）', search_terms = 'MCP 服务,mcp-servers,mcpServers,assembly', updated_at = now()
 WHERE endpoint_key = 'GET /internal/mcp-servers/assembly' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询场景的共数据（项目由会话自动带入，无需手填）', search_terms = '场景,共,mock,hit,count', updated_at = now()
 WHERE endpoint_key = 'GET /internal/mock/hit/count' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询场景数据（项目由会话自动带入，无需手填）', search_terms = '场景,mock,hit,list', updated_at = now()
 WHERE endpoint_key = 'GET /internal/mock/hit/list' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询场景的任务数据（项目由会话自动带入，无需手填）', search_terms = '场景,任务,mock,jobs', updated_at = now()
 WHERE endpoint_key = 'GET /internal/mock/jobs' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看场景的任务数据（项目由会话自动带入，无需手填）', search_terms = '场景,任务,mock,jobs', updated_at = now()
 WHERE endpoint_key = 'GET /internal/mock/jobs/{id}' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看场景的任务数据（项目由会话自动带入，无需手填）', search_terms = '场景,任务,mock,jobs,draftItems,draft-items', updated_at = now()
 WHERE endpoint_key = 'GET /internal/mock/jobs/{id}/draft-items' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询场景的录制中数据（项目由会话自动带入，无需手填）', search_terms = '场景,录制中,mock,recording,frames', updated_at = now()
 WHERE endpoint_key = 'GET /internal/mock/recording/frames' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询场景的录制中数据（项目由会话自动带入，无需手填）', search_terms = '场景,录制中,mock,recording,list', updated_at = now()
 WHERE endpoint_key = 'GET /internal/mock/recording/list' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询场景的录制中数据（项目由会话自动带入，无需手填）', search_terms = '场景,录制中,mock,recording,page', updated_at = now()
 WHERE endpoint_key = 'GET /internal/mock/recording/page' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询场景的预览数据（项目由会话自动带入，无需手填）', search_terms = '场景,预览,录制中,mock,recording,preview', updated_at = now()
 WHERE endpoint_key = 'GET /internal/mock/recording/preview' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询场景数据（项目由会话自动带入，无需手填）', search_terms = '场景,mock,scenario,list', updated_at = now()
 WHERE endpoint_key = 'GET /internal/mock/scenario/list' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询场景数据（项目由会话自动带入，无需手填）', search_terms = '场景,mock,scenario,page', updated_at = now()
 WHERE endpoint_key = 'GET /internal/mock/scenario/page' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询场景数据（项目由会话自动带入，无需手填）', search_terms = '场景,mock,servingInventory,serving-inventory,page', updated_at = now()
 WHERE endpoint_key = 'GET /internal/mock/serving-inventory/page' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询场景的快照状态数据（项目由会话自动带入，无需手填）', search_terms = '场景,快照状态,mock,snapshot', updated_at = now()
 WHERE endpoint_key = 'GET /internal/mock/snapshot' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询场景的仿真空间数据（项目由会话自动带入，无需手填）', search_terms = '场景,仿真空间,mock,space,get', updated_at = now()
 WHERE endpoint_key = 'GET /internal/mock/space/get' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询场景的仿真空间数据（项目由会话自动带入，无需手填）', search_terms = '场景,仿真空间,mock,space,list', updated_at = now()
 WHERE endpoint_key = 'GET /internal/mock/space/list' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询场景的草稿数据（项目由会话自动带入，无需手填）', search_terms = '场景,草稿,mock,stub,drafts', updated_at = now()
 WHERE endpoint_key = 'GET /internal/mock/stub/drafts' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询场景数据（项目由会话自动带入，无需手填）', search_terms = '场景,mock,stub,list', updated_at = now()
 WHERE endpoint_key = 'GET /internal/mock/stub/list' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询场景数据（项目由会话自动带入，无需手填）', search_terms = '场景,mock,stub,page', updated_at = now()
 WHERE endpoint_key = 'GET /internal/mock/stub/page' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询场景的最近访问数据（项目由会话自动带入，无需手填）', search_terms = '场景,最近访问,mock,stub,recent-hits,recentHits', updated_at = now()
 WHERE endpoint_key = 'GET /internal/mock/stub/recent-hits' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询监控中心的定位器数据（项目由会话自动带入，无需手填）', search_terms = '监控中心,定位器,monitor,targets', updated_at = now()
 WHERE endpoint_key = 'GET /internal/monitor/targets' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询监控中心的定位器数据（项目由会话自动带入，无需手填）', search_terms = '监控中心,定位器,monitor,targets,page', updated_at = now()
 WHERE endpoint_key = 'GET /internal/monitor/targets/page' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询死信队列数据（项目由会话自动带入，无需手填）', search_terms = '死信队列,mq-dead-letter,mqDeadLetter,browse', updated_at = now()
 WHERE endpoint_key = 'GET /internal/mq-dead-letter/browse' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询死信队列数据（项目由会话自动带入，无需手填）', search_terms = '死信队列,mq-dead-letter,mqDeadLetter,class-distribution,classDistribution', updated_at = now()
 WHERE endpoint_key = 'GET /internal/mq-dead-letter/class-distribution' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询死信队列数据（项目由会话自动带入，无需手填）', search_terms = '死信队列,mq-dead-letter,mqDeadLetter,queueBacklog,queue-backlog', updated_at = now()
 WHERE endpoint_key = 'GET /internal/mq-dead-letter/queue-backlog' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询订阅数据（项目由会话自动带入，无需手填）', search_terms = '订阅,notificationPrefs,notification-prefs', updated_at = now()
 WHERE endpoint_key = 'GET /internal/notification-prefs' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询通知中心数据（项目由会话自动带入，无需手填）', search_terms = '通知中心,notifications', updated_at = now()
 WHERE endpoint_key = 'GET /internal/notifications' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询压测工作台的超档审批数据（项目由会话自动带入，无需手填）', search_terms = '压测工作台,超档审批,perf,approvals', updated_at = now()
 WHERE endpoint_key = 'GET /internal/perf/approvals' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询压测工作台的数据池（CSV 数据集）数据（项目由会话自动带入，无需手填）', search_terms = '压测工作台,数据池（CSV 数据集）,perf,datasets,page', updated_at = now()
 WHERE endpoint_key = 'GET /internal/perf/datasets/page' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看压测工作台的数据池（CSV 数据集）数据（项目由会话自动带入，无需手填）', search_terms = '压测工作台,数据池（CSV 数据集）,perf,datasets', updated_at = now()
 WHERE endpoint_key = 'GET /internal/perf/datasets/{id}' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询压测工作台数据（项目由会话自动带入，无需手填）', search_terms = '压测工作台,perf,resourcePreflight,resource-preflight', updated_at = now()
 WHERE endpoint_key = 'GET /internal/perf/resource-preflight' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询压测工作台的执行数据（项目由会话自动带入，无需手填）', search_terms = '压测工作台,执行,perf,runs', updated_at = now()
 WHERE endpoint_key = 'GET /internal/perf/runs' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询压测工作台的必填数据（项目由会话自动带入，无需手填）', search_terms = '压测工作台,必填,执行,perf,runs,compare', updated_at = now()
 WHERE endpoint_key = 'GET /internal/perf/runs/compare' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询压测工作台的执行数据（项目由会话自动带入，无需手填）', search_terms = '压测工作台,执行,perf,runs,page', updated_at = now()
 WHERE endpoint_key = 'GET /internal/perf/runs/page' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询压测工作台的项目数据（项目由会话自动带入，无需手填）', search_terms = '压测工作台,项目,执行,perf,runs,projectPage,project-page', updated_at = now()
 WHERE endpoint_key = 'GET /internal/perf/runs/project-page' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看压测工作台的执行数据（项目由会话自动带入，无需手填）', search_terms = '压测工作台,执行,perf,runs', updated_at = now()
 WHERE endpoint_key = 'GET /internal/perf/runs/{runId}' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看压测工作台的判定数据（项目由会话自动带入，无需手填）', search_terms = '压测工作台,判定,执行,perf,runs,ci-verdict,ciVerdict', updated_at = now()
 WHERE endpoint_key = 'GET /internal/perf/runs/{runId}/ci-verdict' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看压测工作台的错误分类统计数据（项目由会话自动带入，无需手填）', search_terms = '压测工作台,错误分类统计,执行,perf,runs,error-topn,errorTopn', updated_at = now()
 WHERE endpoint_key = 'GET /internal/perf/runs/{runId}/error-topn' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看压测工作台的运行事件数据（项目由会话自动带入，无需手填）', search_terms = '压测工作台,运行事件,执行,perf,runs,events', updated_at = now()
 WHERE endpoint_key = 'GET /internal/perf/runs/{runId}/events' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看压测工作台的执行数据（项目由会话自动带入，无需手填）', search_terms = '压测工作台,执行,perf,runs,frames', updated_at = now()
 WHERE endpoint_key = 'GET /internal/perf/runs/{runId}/frames' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看压测工作台的执行数据（项目由会话自动带入，无需手填）', search_terms = '压测工作台,执行,perf,runs,liveCurve,live-curve', updated_at = now()
 WHERE endpoint_key = 'GET /internal/perf/runs/{runId}/live-curve' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看压测工作台的统计报表数据（项目由会话自动带入，无需手填）', search_terms = '压测工作台,统计报表,执行,perf,runs,report', updated_at = now()
 WHERE endpoint_key = 'GET /internal/perf/runs/{runId}/report' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看压测工作台的失败数据（项目由会话自动带入，无需手填）', search_terms = '压测工作台,失败,执行,perf,runs,report-export,reportExport', updated_at = now()
 WHERE endpoint_key = 'GET /internal/perf/runs/{runId}/report-export' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询压测工作台的场景数据（项目由会话自动带入，无需手填）', search_terms = '压测工作台,场景,perf,scenarios', updated_at = now()
 WHERE endpoint_key = 'GET /internal/perf/scenarios' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询压测工作台的场景数据（项目由会话自动带入，无需手填）', search_terms = '压测工作台,场景,perf,scenarios,page', updated_at = now()
 WHERE endpoint_key = 'GET /internal/perf/scenarios/page' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看压测工作台的场景数据（项目由会话自动带入，无需手填）', search_terms = '压测工作台,场景,perf,scenarios', updated_at = now()
 WHERE endpoint_key = 'GET /internal/perf/scenarios/{id}' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看压测工作台的场景数据（项目由会话自动带入，无需手填）', search_terms = '压测工作台,场景,perf,scenarios,deleteImpact,delete-impact', updated_at = now()
 WHERE endpoint_key = 'GET /internal/perf/scenarios/{id}/delete-impact' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看压测工作台的趋势数据（项目由会话自动带入，无需手填）', search_terms = '压测工作台,趋势,场景,perf,scenarios,trend', updated_at = now()
 WHERE endpoint_key = 'GET /internal/perf/scenarios/{id}/trend' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询压测工作台的项目白名单数据（项目由会话自动带入，无需手填）', search_terms = '压测工作台,项目白名单,perf,whitelist', updated_at = now()
 WHERE endpoint_key = 'GET /internal/perf/whitelist' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询计划数据（项目由会话自动带入，无需手填）', search_terms = '计划,plans', updated_at = now()
 WHERE endpoint_key = 'GET /internal/plans' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询计划的处理中数据（项目由会话自动带入，无需手填）', search_terms = '计划,处理中,plans,progress-summary,progressSummary', updated_at = now()
 WHERE endpoint_key = 'GET /internal/plans/progress-summary' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看计划数据（项目由会话自动带入，无需手填）', search_terms = '计划,plans', updated_at = now()
 WHERE endpoint_key = 'GET /internal/plans/{id}' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看计划数据（项目由会话自动带入，无需手填）', search_terms = '计划,plans,case-allowed,caseAllowed', updated_at = now()
 WHERE endpoint_key = 'GET /internal/plans/{id}/case-allowed' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看计划的用例数据（项目由会话自动带入，无需手填）', search_terms = '计划,用例,plans,cases', updated_at = now()
 WHERE endpoint_key = 'GET /internal/plans/{id}/cases' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看计划的偏差率数据（项目由会话自动带入，无需手填）', search_terms = '计划,偏差率,plans,deviations', updated_at = now()
 WHERE endpoint_key = 'GET /internal/plans/{id}/deviations' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看计划的进度数据（项目由会话自动带入，无需手填）', search_terms = '计划,进度,plans,progress', updated_at = now()
 WHERE endpoint_key = 'GET /internal/plans/{id}/progress' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询回收站数据（项目由会话自动带入，无需手填）', search_terms = '回收站,projectMemory,project-memory,recycle-bin,recycleBin', updated_at = now()
 WHERE endpoint_key = 'GET /internal/project-memory/recycle-bin' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询项目数据（项目由会话自动带入，无需手填）', search_terms = '项目,projects', updated_at = now()
 WHERE endpoint_key = 'GET /internal/projects' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看项目的环境数据（项目由会话自动带入，无需手填）', search_terms = '项目,环境,projects,envs', updated_at = now()
 WHERE endpoint_key = 'GET /internal/projects/envs/{envId}' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看项目的环境数据（项目由会话自动带入，无需手填）', search_terms = '项目,环境,projects,envs,deployInfo,deploy-info', updated_at = now()
 WHERE endpoint_key = 'GET /internal/projects/envs/{envId}/deploy-info' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询项目的模板数据（项目由会话自动带入，无需手填）', search_terms = '项目,模板,projects,templates', updated_at = now()
 WHERE endpoint_key = 'GET /internal/projects/templates' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看项目的模板数据（项目由会话自动带入，无需手填）', search_terms = '项目,模板,projects,templates', updated_at = now()
 WHERE endpoint_key = 'GET /internal/projects/templates/{templateId}' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看项目数据（项目由会话自动带入，无需手填）', search_terms = '项目,projects', updated_at = now()
 WHERE endpoint_key = 'GET /internal/projects/{id}' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看项目的环境数据（项目由会话自动带入，无需手填）', search_terms = '项目,环境,projects,envs', updated_at = now()
 WHERE endpoint_key = 'GET /internal/projects/{id}/envs' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看项目的调度健康卡数据（项目由会话自动带入，无需手填）', search_terms = '项目,调度健康卡,环境,projects,envs,health', updated_at = now()
 WHERE endpoint_key = 'GET /internal/projects/{id}/envs/{envId}/health' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看项目的参数数据（项目由会话自动带入，无需手填）', search_terms = '项目,参数,环境,projects,envs,params', updated_at = now()
 WHERE endpoint_key = 'GET /internal/projects/{id}/envs/{envId}/params' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看项目的角色数据（项目由会话自动带入，无需手填）', search_terms = '项目,角色,projects,memberRole,member-role', updated_at = now()
 WHERE endpoint_key = 'GET /internal/projects/{id}/member-role' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看项目的成员数据（项目由会话自动带入，无需手填）', search_terms = '项目,成员,projects,members', updated_at = now()
 WHERE endpoint_key = 'GET /internal/projects/{id}/members' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看项目的覆盖矩阵数据（项目由会话自动带入，无需手填）', search_terms = '项目,覆盖矩阵,projects,coverage', updated_at = now()
 WHERE endpoint_key = 'GET /internal/projects/{projectId}/coverage' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看项目数据（项目由会话自动带入，无需手填）', search_terms = '项目,projects,embeddingConfig,embedding-config,brief', updated_at = now()
 WHERE endpoint_key = 'GET /internal/projects/{projectId}/embedding-config/brief' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看项目的数据源连接数据（项目由会话自动带入，无需手填）', search_terms = '项目,数据源连接,projects,env-connections,envConnections', updated_at = now()
 WHERE endpoint_key = 'GET /internal/projects/{projectId}/env-connections' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看项目的模型配置数据（项目由会话自动带入，无需手填）', search_terms = '项目,模型配置,projects,llm-config,llmConfig', updated_at = now()
 WHERE endpoint_key = 'GET /internal/projects/{projectId}/llm-config' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看项目的模型配置数据（项目由会话自动带入，无需手填）', search_terms = '项目,模型配置,projects,llm-config,llmConfig,brief', updated_at = now()
 WHERE endpoint_key = 'GET /internal/projects/{projectId}/llm-config/brief' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看项目的行业插件数据（项目由会话自动带入，无需手填）', search_terms = '项目,行业插件,projects,plugins', updated_at = now()
 WHERE endpoint_key = 'GET /internal/projects/{projectId}/plugins' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询提示词模板的版本数据（项目由会话自动带入，无需手填）', search_terms = '提示词模板,版本,prompt-templates,promptTemplates,families', updated_at = now()
 WHERE endpoint_key = 'GET /internal/prompt-templates/families' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询提示词模板数据（项目由会话自动带入，无需手填）', search_terms = '提示词模板,prompt-templates,promptTemplates,latest', updated_at = now()
 WHERE endpoint_key = 'GET /internal/prompt-templates/latest' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询提示词模板的状态数据（项目由会话自动带入，无需手填）', search_terms = '提示词模板,状态,prompt-templates,promptTemplates,latestByStatus,latest-by-status', updated_at = now()
 WHERE endpoint_key = 'GET /internal/prompt-templates/latest-by-status' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询提示词模板的版本数据（项目由会话自动带入，无需手填）', search_terms = '提示词模板,版本,prompt-templates,promptTemplates,versions', updated_at = now()
 WHERE endpoint_key = 'GET /internal/prompt-templates/versions' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看提示词模板数据（项目由会话自动带入，无需手填）', search_terms = '提示词模板,prompt-templates,promptTemplates', updated_at = now()
 WHERE endpoint_key = 'GET /internal/prompt-templates/{id}' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询角色管理的AI 质量报告数据（项目由会话自动带入，无需手填）', search_terms = '角色管理,AI 质量报告,统计报表,reports,ai-quality,aiQuality', updated_at = now()
 WHERE endpoint_key = 'GET /internal/reports/ai-quality' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询角色管理的必填数据（项目由会话自动带入，无需手填）', search_terms = '角色管理,必填,统计报表,reports,compare', updated_at = now()
 WHERE endpoint_key = 'GET /internal/reports/compare' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询角色管理的覆盖矩阵数据（项目由会话自动带入，无需手填）', search_terms = '角色管理,覆盖矩阵,统计报表,reports,coverage', updated_at = now()
 WHERE endpoint_key = 'GET /internal/reports/coverage' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询角色管理的执行结果统计数据（项目由会话自动带入，无需手填）', search_terms = '角色管理,执行结果统计,统计报表,reports,execution-stat,executionStat', updated_at = now()
 WHERE endpoint_key = 'GET /internal/reports/execution-stat' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询角色管理的导出任务数据（项目由会话自动带入，无需手填）', search_terms = '角色管理,导出任务,统计报表,reports,exportTasks,export-tasks', updated_at = now()
 WHERE endpoint_key = 'GET /internal/reports/export-tasks' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看角色管理的下载数据（项目由会话自动带入，无需手填）', search_terms = '角色管理,下载,统计报表,导出任务,reports,exportTasks,export-tasks,download', updated_at = now()
 WHERE endpoint_key = 'GET /internal/reports/export-tasks/{id}/download' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询角色管理的个人评审进度数据（项目由会话自动带入，无需手填）', search_terms = '角色管理,个人评审进度,统计报表,reports,personal-review,personalReview', updated_at = now()
 WHERE endpoint_key = 'GET /internal/reports/personal-review' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询角色管理的用例阶段分布数据（项目由会话自动带入，无需手填）', search_terms = '角色管理,用例阶段分布,统计报表,reports,phaseDist,phase-dist', updated_at = now()
 WHERE endpoint_key = 'GET /internal/reports/phase-dist' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询角色管理的团队评审看板数据（项目由会话自动带入，无需手填）', search_terms = '角色管理,团队评审看板,统计报表,reports,teamBoard,team-board', updated_at = now()
 WHERE endpoint_key = 'GET /internal/reports/team-board' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询需求数据（项目由会话自动带入，无需手填）', search_terms = '需求,requirements', updated_at = now()
 WHERE endpoint_key = 'GET /internal/requirements' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询需求的未覆盖数据（项目由会话自动带入，无需手填）', search_terms = '需求,未覆盖,requirements,uncovered', updated_at = now()
 WHERE endpoint_key = 'GET /internal/requirements/uncovered' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看角色管理的数据范围数据（项目由会话自动带入，无需手填）', search_terms = '角色管理,数据范围,角色,用户,roles,users,dataScope,data-scope', updated_at = now()
 WHERE endpoint_key = 'GET /internal/roles/users/{userId}/data-scope' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看角色管理的权限规则引用数据（项目由会话自动带入，无需手填）', search_terms = '角色管理,权限规则引用,角色,用户,roles,users,perms', updated_at = now()
 WHERE endpoint_key = 'GET /internal/roles/users/{userId}/perms' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看角色数据（项目由会话自动带入，无需手填）', search_terms = '角色管理,角色,用户,roles,users', updated_at = now()
 WHERE endpoint_key = 'GET /internal/roles/users/{userId}/roles' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询定时配置的定时触发记录数据（项目由会话自动带入，无需手填）', search_terms = '定时配置,定时触发记录,schedule,fires', updated_at = now()
 WHERE endpoint_key = 'GET /internal/schedule/fires' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询搜索数据（项目由会话自动带入，无需手填）', search_terms = '搜索,search', updated_at = now()
 WHERE endpoint_key = 'GET /internal/search' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询SKILL 管理数据（项目由会话自动带入，无需手填）', search_terms = 'SKILL 管理,skills', updated_at = now()
 WHERE endpoint_key = 'GET /internal/skills' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询SKILL 管理的工具市场数据（项目由会话自动带入，无需手填）', search_terms = 'SKILL 管理,工具市场,skills,market', updated_at = now()
 WHERE endpoint_key = 'GET /internal/skills/market' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看SKILL 管理的项目数据（项目由会话自动带入，无需手填）', search_terms = 'SKILL 管理,项目,skills,project', updated_at = now()
 WHERE endpoint_key = 'GET /internal/skills/project/{pid}' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看SKILL 管理的项目数据（项目由会话自动带入，无需手填）', search_terms = 'SKILL 管理,项目,skills,project,assembly', updated_at = now()
 WHERE endpoint_key = 'GET /internal/skills/project/{pid}/assembly' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询SKILL 管理的回收站数据（项目由会话自动带入，无需手填）', search_terms = 'SKILL 管理,回收站,skills,recycle-bin,recycleBin', updated_at = now()
 WHERE endpoint_key = 'GET /internal/skills/recycle-bin' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看SKILL 管理数据（项目由会话自动带入，无需手填）', search_terms = 'SKILL 管理,skills', updated_at = now()
 WHERE endpoint_key = 'GET /internal/skills/{id}' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看SKILL 管理的版本数据（项目由会话自动带入，无需手填）', search_terms = 'SKILL 管理,版本,skills,versions', updated_at = now()
 WHERE endpoint_key = 'GET /internal/skills/{id}/versions' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看SKILL 管理的版本数据（项目由会话自动带入，无需手填）', search_terms = 'SKILL 管理,版本,skills,versions', updated_at = now()
 WHERE endpoint_key = 'GET /internal/skills/{id}/versions/{version}' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询回归集数据（项目由会话自动带入，无需手填）', search_terms = '回归集,suites', updated_at = now()
 WHERE endpoint_key = 'GET /internal/suites' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询标签数据（项目由会话自动带入，无需手填）', search_terms = '标签,tags', updated_at = now()
 WHERE endpoint_key = 'GET /internal/tags' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询标签数据（项目由会话自动带入，无需手填）', search_terms = '标签,tags,stats', updated_at = now()
 WHERE endpoint_key = 'GET /internal/tags/stats' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询标签的没有可见项目数据（项目由会话自动带入，无需手填）', search_terms = '标签,没有可见项目,tags,stats,visible', updated_at = now()
 WHERE endpoint_key = 'GET /internal/tags/stats/visible' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询标签的没有可见项目数据（项目由会话自动带入，无需手填）', search_terms = '标签,没有可见项目,tags,visible', updated_at = now()
 WHERE endpoint_key = 'GET /internal/tags/visible' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询角色管理的总结报告数据（项目由会话自动带入，无需手填）', search_terms = '角色管理,总结报告,test-reports,testReports', updated_at = now()
 WHERE endpoint_key = 'GET /internal/test-reports' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询工具管理数据（项目由会话自动带入，无需手填）', search_terms = '工具管理,tools', updated_at = now()
 WHERE endpoint_key = 'GET /internal/tools' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询工具管理的工具市场数据（项目由会话自动带入，无需手填）', search_terms = '工具管理,工具市场,tools,market', updated_at = now()
 WHERE endpoint_key = 'GET /internal/tools/market' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看工具管理的下载数据（项目由会话自动带入，无需手填）', search_terms = '工具管理,下载,工具市场,tools,market,download', updated_at = now()
 WHERE endpoint_key = 'GET /internal/tools/market/{id}/download' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看工具管理的项目数据（项目由会话自动带入，无需手填）', search_terms = '工具管理,项目,tools,project', updated_at = now()
 WHERE endpoint_key = 'GET /internal/tools/project/{pid}' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看工具管理的项目数据（项目由会话自动带入，无需手填）', search_terms = '工具管理,项目,tools,project,assembly', updated_at = now()
 WHERE endpoint_key = 'GET /internal/tools/project/{pid}/assembly' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看工具管理的项目数据（项目由会话自动带入，无需手填）', search_terms = '工具管理,项目,tools,project,draftKinds,draft-kinds', updated_at = now()
 WHERE endpoint_key = 'GET /internal/tools/project/{pid}/draft-kinds' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看工具管理数据（项目由会话自动带入，无需手填）', search_terms = '工具管理,tools', updated_at = now()
 WHERE endpoint_key = 'GET /internal/tools/{id}' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询训练样本数据（项目由会话自动带入，无需手填）', search_terms = '训练样本,training-samples,trainingSamples', updated_at = now()
 WHERE endpoint_key = 'GET /internal/training-samples' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询训练样本数据（项目由会话自动带入，无需手填）', search_terms = '训练样本,training-samples,trainingSamples,wording-pairs,wordingPairs', updated_at = now()
 WHERE endpoint_key = 'GET /internal/training-samples/wording-pairs' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查询用户数据（项目由会话自动带入，无需手填）', search_terms = '用户,user-projects,userProjects', updated_at = now()
 WHERE endpoint_key = 'GET /internal/user-projects' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看死信队列的作废数据（项目由会话自动带入，无需手填）', search_terms = '死信队列,作废,mq-dead-letter,mqDeadLetter,void', updated_at = now()
 WHERE endpoint_key = 'POST /internal/mq-dead-letter/void' AND reachable = 1;
UPDATE tp_ai_api_access_governance
   SET ai_desc = '按项目范围查看标签数据（项目由会话自动带入，无需手填）', search_terms = '标签,tags', updated_at = now()
 WHERE endpoint_key = 'POST /internal/tags' AND reachable = 1;
