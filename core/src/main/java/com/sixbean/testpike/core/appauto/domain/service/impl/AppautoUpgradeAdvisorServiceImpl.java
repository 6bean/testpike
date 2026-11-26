package com.sixbean.testpike.core.appauto.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.pipeline.DenialGuidanceFrame;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.UiActionContract;
import com.sixbean.testpike.core.appauto.api.dto.AppautoCaseFeatureProfile;
import com.sixbean.testpike.core.appauto.api.dto.AppautoUpgradeAdviceRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoUpgradeAdviceView;
import com.sixbean.testpike.core.appauto.api.dto.AppautoUpgradeAdvisorLlmRequest;
import com.sixbean.testpike.core.appauto.domain.component.AppautoConfigReader;
import com.sixbean.testpike.core.appauto.domain.service.AppautoUpgradeAdvisorService;
import com.sixbean.testpike.core.appauto.infrastructure.remote.client.AiServiceUpgradeAdvisorClient;
import com.sixbean.testpike.core.executor.domain.component.AppAutoDispatchSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;


@Service
public class AppautoUpgradeAdvisorServiceImpl implements AppautoUpgradeAdvisorService {

    private final AppautoConfigReader configReader;
    private final AiServiceUpgradeAdvisorClient advisorClient;
    private final AppAutoDispatchSupport dispatchSupport;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AppautoUpgradeAdvisorServiceImpl(AppautoConfigReader configReader,
                                            AiServiceUpgradeAdvisorClient advisorClient,
                                            AppAutoDispatchSupport dispatchSupport,
                                            JdbcTemplate jdbcTemplate,
                                            ObjectMapper objectMapper) {
        this.configReader = configReader;
        this.advisorClient = advisorClient;
        this.dispatchSupport = dispatchSupport;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public AppautoUpgradeAdviceView advise(AppautoUpgradeAdviceRequest request) {
        if (!configReader.upgradeAdvisorEnabled()) {
            throw new ServiceException(DenialGuidanceFrame.switchClosed("升级顾问",
                    AppautoConfigReader.KEY_UPGRADE_ADVISOR), BizErrorCode.FORBIDDEN);
        }
        if (request == null || request.projectId() == null) {
            throw new ServiceException("升级顾问缺少项目号（用例特征画像与成本归属依据）"
                    + "——请在「升级中心」选择具体项目后发起征询（参数 projectId）", BizErrorCode.VALIDATION_ERROR);
        }
        if (request.manifestVersion() == null || request.manifestVersion().isBlank()) {
            throw new ServiceException("升级顾问缺少目标发版本号（要升到哪一版）"
                    + "——请到「机群 → 升级中心」选择目标版本后发起", BizErrorCode.VALIDATION_ERROR);
        }
        List<String> breakingChanges = requireManifestRow(request.manifestVersion().trim());
        AppautoCaseFeatureProfile profile = computeProfile(request.projectId());

        Map<String, Object> answer = advisorClient.advise(new AppautoUpgradeAdvisorLlmRequest(
                request.projectId(), request.manifestVersion().trim(), breakingChanges, profile));
        List<String> risks = requireRiskRows(answer);
        String summary = answer.get("summary") == null ? "" : String.valueOf(answer.get("summary"));
        return new AppautoUpgradeAdviceView(risks, summary, breakingChanges, profile,
                conclusion(profile));
    }

    
    private List<String> requireManifestRow(String version) {
        String json = jdbcTemplate.query(
                "SELECT breaking_changes::text FROM tp_release_manifest WHERE version = ?",
                rs -> rs.next() ? rs.getString(1) : null, version);
        if (json == null) {
            throw new ServiceException("发布清单里没有版本「" + version + "」——升级前请先在"
                    + "「机群 → 升级中心」登记该版本的发布清单（含破坏性变更）再征询顾问",
                    BizErrorCode.VALIDATION_ERROR);
        }
        try {
            List<String> out = new ArrayList<>();
            JsonNode node = objectMapper.readTree(json);
            for (JsonNode item : node) {
                String text = item.isTextual() ? item.asText() : item.toString();
                if (!text.isBlank()) {
                    out.add(text);
                }
            }
            return List.copyOf(out);
        } catch (Exception e) {
            throw new ServiceException("发布清单「" + version + "」的破坏性变更字段不是合法"
                    + " JSON 数组——请到「升级中心」重新登记该版本清单"
                    + "（字段 breakingChanges，格式：JSON 字符串数组）", BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    private AppautoCaseFeatureProfile computeProfile(long projectId) {
        Map<String, Integer> verbCounts = new TreeMap<>();
        int[] scriptStats = countScriptFeatures(projectId, verbCounts);
        Map<String, Integer> engineUsage = countEngineUsage(projectId);
        Integer aiCases = jdbcTemplate.queryForObject("""
                SELECT count(DISTINCT case_id) FROM tp_appauto_ai_sample WHERE project_id = ?
                """, Integer.class, projectId);
        return new AppautoCaseFeatureProfile(scriptStats[0], Map.copyOf(verbCounts),
                Map.copyOf(engineUsage), aiCases == null ? 0 : aiCases, scriptStats[1]);
    }

    
    private int[] countScriptFeatures(long projectId, Map<String, Integer> verbCounts) {
        int scripts = 0;
        int webviewSteps = 0;
        List<String> actionJsons = jdbcTemplate.queryForList("""
                SELECT s.action_json::text FROM tp_script s
                JOIN tp_test_case c ON c.id = s.case_id
                WHERE c.project_id = ? AND c.deleted = 0
                """, String.class, projectId);
        for (String json : actionJsons) {
            scripts++;
            try {
                JsonNode steps = objectMapper.readTree(json).path("steps");
                if (!steps.isArray()) {
                    continue;
                }
                for (JsonNode step : steps) {
                    if (!step.isObject()) {
                        continue;
                    }
                    String action = step.path("action").asText("");
                    if (UiActionContract.isAppAction(action)) {
                        verbCounts.merge(action, 1, Integer::sum);
                    }
                    if (UiActionContract.stepRequestsWebview(
                            objectMapper.convertValue(step, Map.class))) {
                        webviewSteps++;
                    }
                }
            } catch (Exception e) {
            }
        }
        return new int[]{scripts, webviewSteps};
    }

    
    private Map<String, Integer> countEngineUsage(long projectId) {
        Map<String, Integer> usage = new LinkedHashMap<>();
        List<String> envelopes = jdbcTemplate.queryForList("""
                SELECT action_json FROM tp_exec_task
                WHERE project_id = ? AND task_type = 6 AND status IN (3, 4, 5)
                """, String.class, projectId);
        for (String envelope : envelopes) {
            String code = dispatchSupport.envelopeEngineCode(envelope);
            if (code != null && !code.isBlank()) {
                usage.merge(code.trim().toUpperCase(Locale.ROOT), 1, Integer::sum);
            }
        }
        return usage;
    }

    
    private List<String> requireRiskRows(Map<String, Object> answer) {
        Object node = answer == null ? null : answer.get("risks");
        if (!(node instanceof List<?> list)) {
            throw new ServiceException("模型没有产出升级风险清单（应答缺少 risks 数组）"
                    + "——本次征询不采用，请稍后在「升级中心」重新发起征询", BizErrorCode.AI_FORMAT_ERROR);
        }
        List<String> risks = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw new ServiceException("模型产出的风险条目不是可读文本"
                        + "——本次征询不采用，请在「升级中心」重新征询（示例：要求逐条纯文本输出）",
                        BizErrorCode.AI_FORMAT_ERROR);
            }
            risks.add(text);
        }
        return List.copyOf(risks);
    }

 
    private String conclusion(AppautoCaseFeatureProfile profile) {
        return profile.scriptCases() + " 个用例有现行脚本（app.* 动词 "
                + profile.verbCounts().values().stream().mapToInt(Integer::intValue).sum()
                + " 步、webview 混合落区 " + profile.webviewSteps()
                + " 步），历史用过引擎 " + profile.engineUsage().size()
                + " 种、" + profile.aiSampleCases() + " 个用例沉淀过 AI 定位样本——"
                + "破坏性变更命中以上特征的风险条目见清单";
    }
}
