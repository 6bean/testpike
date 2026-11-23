package com.sixbean.testpike.core.appauto.domain.service.impl;

import com.sixbean.testpike.common.pipeline.DenialGuidanceFrame;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.appauto.api.dto.AppautoEngineInfo;
import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixCombo;
import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixSampleCandidate;
import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixSampleLlmRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixSampleProfile;
import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixSampleRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixSampleView;
import com.sixbean.testpike.core.appauto.api.dto.AppautoPlatformInfo;
import com.sixbean.testpike.core.appauto.domain.component.AppautoConfigReader;
import com.sixbean.testpike.core.appauto.domain.component.AppautoMatrixDeviceResolver;
import com.sixbean.testpike.core.appauto.domain.service.AppautoEngineService;
import com.sixbean.testpike.core.appauto.domain.service.AppautoMatrixSampleService;
import com.sixbean.testpike.core.appauto.domain.service.AppautoPlatformService;
import com.sixbean.testpike.core.appauto.infrastructure.remote.client.AiServiceMatrixSampleClient;
import com.sixbean.testpike.core.batch.domain.component.BatchQueueEstimateSupport;
import com.sixbean.testpike.core.executor.domain.component.AppAutoDispatchSupport;
import com.sixbean.testpike.core.script.api.dto.ScriptInfo;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;


@Service
public class AppautoMatrixSampleServiceImpl implements AppautoMatrixSampleService {

    
    private static final int STATUS_PASS = 3;

    private final AppautoConfigReader configReader;
    private final AppautoEngineService engineService;
    private final AppautoPlatformService platformService;
    private final AppautoMatrixDeviceResolver deviceResolver;
    private final AiServiceMatrixSampleClient sampleClient;
    private final BatchQueueEstimateSupport queueEstimateSupport;
    private final AppAutoDispatchSupport dispatchSupport;
    private final ScriptService scriptService;
    private final JdbcTemplate jdbcTemplate;

    public AppautoMatrixSampleServiceImpl(AppautoConfigReader configReader,
                                          AppautoEngineService engineService,
                                          AppautoPlatformService platformService,
                                          AppautoMatrixDeviceResolver deviceResolver,
                                          AiServiceMatrixSampleClient sampleClient,
                                          BatchQueueEstimateSupport queueEstimateSupport,
                                          AppAutoDispatchSupport dispatchSupport,
                                          ScriptService scriptService,
                                          JdbcTemplate jdbcTemplate) {
        this.configReader = configReader;
        this.engineService = engineService;
        this.platformService = platformService;
        this.deviceResolver = deviceResolver;
        this.sampleClient = sampleClient;
        this.queueEstimateSupport = queueEstimateSupport;
        this.dispatchSupport = dispatchSupport;
        this.scriptService = scriptService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public AppautoMatrixSampleView recommend(AppautoMatrixSampleRequest request) {
        if (!configReader.matrixSampleEnabled()) {
            throw new ServiceException(DenialGuidanceFrame.switchClosed("矩阵智能抽样",
                    AppautoConfigReader.KEY_MATRIX_SAMPLE), BizErrorCode.FORBIDDEN);
        }
        if (request == null || request.projectId() == null) {
            throw new ServiceException("矩阵抽样缺少项目号（候选画像与成本归属依据）"
                    + "——请从具体项目的「用例详情」发起推荐", BizErrorCode.VALIDATION_ERROR);
        }
        if (request.caseId() == null) {
            throw new ServiceException("矩阵抽样缺少用例号（按该用例的 app.* 步骤语境展开候选面）"
                    + "——请在具体用例的「用例详情」矩阵推荐面板发起",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Map<String, Object> caseRow = loadCase(request.projectId(), request.caseId());
        String target = String.valueOf(caseRow.get("automation_target"));
        if (!"app".equals(target) && !"mp".equals(target)) {
            throw new ServiceException("用例「" + caseRow.get("title") + "」未挂 App/小程序自动化目标，"
                    + "矩阵抽样无候选语境——请先在「用例详情」选择自动化目标", BizErrorCode.VALIDATION_ERROR);
        }
        List<Map<String, Object>> appSteps = requireAppSteps(request.caseId());

        int fanoutMax = configReader.matrixFanoutMax();
        int topN = resolveTopN(request.topN(), fanoutMax);

        List<AppautoMatrixSampleCandidate> candidates = enumerateCandidates(request.projectId());
        topN = Math.min(topN, Math.max(candidates.size(), 1));
        AppautoMatrixSampleProfile profile = buildProfile(request.projectId(), candidates);

        Map<String, Object> answer = sampleClient.sample(new AppautoMatrixSampleLlmRequest(
                request.projectId(), request.caseId(), candidates, profile, topN));
        List<AppautoMatrixCombo> recommendations =
                requireRecommendations(answer, candidates, topN, appSteps.size());

        return new AppautoMatrixSampleView(recommendations,
                coverageMessage(candidates, profile), costMessage(recommendations, profile),
                answer.get("summary") == null ? "" : String.valueOf(answer.get("summary")));
    }

    
    private Map<String, Object> loadCase(long projectId, long caseId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, title, automation_target FROM tp_test_case
                WHERE id = ? AND project_id = ? AND deleted = 0
                """, caseId, projectId);
        if (rows.isEmpty()) {
            throw new ServiceException("用例不存在或不属于当前项目，已拒绝跨项目抽样推荐"
                    + "——请切换到用例所属项目，再从该用例的「用例详情」发起",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return rows.get(0);
    }

    
    private List<Map<String, Object>> requireAppSteps(long caseId) {
        ScriptInfo script = scriptService.getByCaseId(caseId);
        List<Map<String, Object>> steps = script == null ? List.of()
                : dispatchSupport.extractAppSteps(script.actionJson());
        if (steps.isEmpty()) {
            throw new ServiceException("该用例未编排 app.* 动作步骤，矩阵抽样无候选语境——"
                    + "请先在「用例详情」编排 App 步骤（正确写法示例 app.launch、app.tap）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return steps;
    }

    
    private int resolveTopN(Integer topN, int fanoutMax) {
        if (topN == null) {
            return fanoutMax;
        }
        if (topN <= 0) {
            throw new ServiceException("矩阵抽样的推荐条数必须是正整数（希望模型推荐几个组合）"
                    + "——请把条数改成正整数（正确写法示例：5）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (topN > fanoutMax) {
            throw new ServiceException("矩阵抽样的推荐条数 " + topN + " 超过单用例扇出上限 "
                    + fanoutMax + "（配置键 appauto.matrix-fanout-max 可热更调高）——"
                    + "推荐面不会超过可派发面——请把条数降到上限以内，或到「系统设置」调高 appauto.matrix-fanout-max",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return topN;
    }

    
    private List<AppautoMatrixSampleCandidate> enumerateCandidates(long projectId) {
        List<AppautoPlatformInfo> platforms = platformService.listPlatforms(false);
        Map<String, Set<String>> kindsByPlatform = new LinkedHashMap<>();
        for (AppautoPlatformInfo p : platforms) {
            kindsByPlatform.put(norm(p.code()), new LinkedHashSet<>(upperAll(p.deviceKindCodes())));
        }
        List<AppautoMatrixSampleCandidate> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (AppautoEngineInfo engine : engineService.listEngines(false)) {
            if (engine.status() != 1) {
                continue;
            }
            Set<String> engineKinds = new LinkedHashSet<>(upperAll(engine.deviceKindCodes()));
            for (String platformCode : upperAll(engine.platformCodes())) {
                Set<String> platformKinds = kindsByPlatform.get(platformCode);
                if (platformKinds == null) {
                    continue;
                }
                for (String kind : platformKinds) {
                    if (!engineKinds.contains(kind)) {
                        continue;
                    }
                    String key = engine.code().toUpperCase(Locale.ROOT) + "|" + platformCode + "|" + kind;
                    if (!seen.add(key)) {
                        continue;
                    }
                    out.add(new AppautoMatrixSampleCandidate(
                            norm(engine.code()), platformCode, kind,
                            deviceResolver.resolve(projectId, kind, 0, null, platformCode) != null));
                }
            }
        }
        return List.copyOf(out);
    }

    
    private AppautoMatrixSampleProfile buildProfile(long projectId,
                                                    List<AppautoMatrixSampleCandidate> candidates) {
        int installedAxes = Integer.parseInt(String.valueOf(jdbcTemplate.queryForObject("""
                SELECT count(DISTINCT (model, COALESCE(os_version, '未知')))
                FROM tp_appauto_device
                WHERE project_id = ? AND model IS NOT NULL AND trim(model) <> ''
                """, Long.class, projectId)));
        Map<String, Double> passRate = engineRecentPassRate(projectId);
        return new AppautoMatrixSampleProfile(candidates.size(), installedAxes, passRate,
                queueEstimateSupport.minutesPerTask(), configReader.matrixFanoutMax());
    }

    
    private Map<String, Double> engineRecentPassRate(long projectId) {
        int windowDays = configReader.supportWindowDays();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT action_json, status FROM tp_exec_task
                WHERE task_type = 6 AND project_id = ? AND status IN (3, 4, 5)
                  AND COALESCE(finished_at, started_at, created_at)
                      >= now() - make_interval(days => ?::int)
                """, projectId, windowDays);
        Map<String, long[]> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String code = dispatchSupport.envelopeEngineCode((String) row.get("action_json"));
            if (code == null || code.isBlank()) {
                continue;
            }
            long[] c = counts.computeIfAbsent(code.trim().toUpperCase(Locale.ROOT), k -> new long[2]);
            c[0]++;
            if (((Number) row.get("status")).intValue() == STATUS_PASS) {
                c[1]++;
            }
        }
        Map<String, Double> rate = new LinkedHashMap<>();
        for (Map.Entry<String, long[]> e : counts.entrySet()) {
            rate.put(e.getKey(), Math.round((double) e.getValue()[1] / e.getValue()[0] * 10000.0) / 100.0);
        }
        return rate;
    }

    
    private List<AppautoMatrixCombo> requireRecommendations(Map<String, Object> answer,
                                                            List<AppautoMatrixSampleCandidate> candidates,
                                                            int topN, int appStepCount) {
        Object node = answer == null ? null : answer.get("recommendations");
        if (!(node instanceof List<?> list)) {
            throw new ServiceException("模型没有产出推荐组合清单（应答缺少 recommendations 数组）"
                    + "——本次推荐不采用，请到「用例详情」稍后重试", BizErrorCode.AI_FORMAT_ERROR);
        }
        Set<String> face = new LinkedHashSet<>();
        for (AppautoMatrixSampleCandidate c : candidates) {
            face.add(c.engineCode() + "|" + c.platformCode() + "|" + c.deviceKindCode());
        }
        List<AppautoMatrixCombo> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> row)) {
                continue;
            }
            AppautoMatrixCombo combo = new AppautoMatrixCombo(
                    norm(str(row.get("engineCode"))), norm(str(row.get("platformCode"))),
                    norm(str(row.get("deviceKindCode"))), null);
            String key = combo.engineCode() + "|" + combo.platformCode() + "|" + combo.deviceKindCode();
            if (combo.engineCode() == null || combo.platformCode() == null
                    || combo.deviceKindCode() == null || !face.contains(key) || !seen.add(key)) {
                continue;
            }
            out.add(combo);
            if (out.size() >= topN) {
                break;
            }
        }
        if (out.isEmpty() && !list.isEmpty()) {
            throw new ServiceException("模型推荐的组合全部越出可派发候选面（幻觉组合不采信）"
                    + "——本次推荐不采用，请到「用例详情」人工勾选组合，或稍后重试", BizErrorCode.AI_FORMAT_ERROR);
        }
        return List.copyOf(out);
    }

    
    private String coverageMessage(List<AppautoMatrixSampleCandidate> candidates,
                                   AppautoMatrixSampleProfile profile) {
        long addressable = candidates.stream().filter(AppautoMatrixSampleCandidate::addressable).count();
        return "候选组合 " + candidates.size() + " 个（其中可寻址设备档案 " + addressable
                + " 个），项目机型现装面 " + profile.installedModelAxes()
                + " 轴（品名×OS 版本），近 " + configReader.supportWindowDays()
                + " 天引擎通过率 " + (profile.engineRecentPassRate().isEmpty()
                        ? "暂无终态样本" : profile.engineRecentPassRate() + "%");
    }

    
    private String costMessage(List<AppautoMatrixCombo> recommendations,
                               AppautoMatrixSampleProfile profile) {
        long ahead = queueEstimateSupport.pendingAheadCount(null);
        long capacity = Math.max(queueEstimateSupport.idleCapacity(), 1);
        long queueMinutes = queueEstimateSupport.estimateMinutes(ahead, capacity);
        return "推荐 " + recommendations.size() + " 组合 × 每任务约 " + profile.minutesPerTask()
                + " 分钟 ≈ " + (recommendations.size() * (long) profile.minutesPerTask())
                + " 分钟机时；当前队列前有待派发 " + ahead + " 任务，预计排队约 " + queueMinutes
                + " 分钟——推荐仅供参考，确认后才走扇出派发";
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String norm(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static List<String> upperAll(List<String> values) {
        return values == null ? List.of() : values.stream().map(AppautoMatrixSampleServiceImpl::norm)
                .filter(v -> v != null && !v.isEmpty()).toList();
    }
}
