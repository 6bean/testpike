package com.sixbean.testpike.aiservice.repair.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.LlmAuditRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.AiCallLogWriter;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelAssembly;
import com.sixbean.testpike.aiservice.repair.domain.component.RepairPromptTemplate;
import com.sixbean.testpike.aiservice.repair.domain.service.ScriptRepairLoopService;
import com.sixbean.testpike.aiservice.repair.infrastructure.remote.RepairTaskView;
import com.sixbean.testpike.common.mq.ExecFailedEventMessage;
import com.sixbean.testpike.common.util.ActionCheckResult;
import com.sixbean.testpike.common.util.ActionJsonValidator;
import com.sixbean.testpike.common.util.JsonOutputExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigBooleans;


@Service
public class ScriptRepairLoopServiceImpl implements ScriptRepairLoopService {

    private static final Logger log = LoggerFactory.getLogger(ScriptRepairLoopServiceImpl.class);

    
    static final String KEY_ENABLED = "ai.repair.enabled";
    
    static final String KEY_ROUNDS = "ai.repair.rounds";
    
    static final String KEY_MODEL = "ai.repair.model";
    
    static final int DEFAULT_ROUNDS = 3;
    
    private static final int CLASS_PRODUCT_DEFECT = 1;
    private static final int CLASS_AI_LOCATOR = 2;
    private static final int CLASS_ENVIRONMENT = 3;
    
    private static final int OUTCOME_PASSED = 1;
    private static final int OUTCOME_NOT_PASSED = 2;
    private static final int OUTCOME_GATE_SKIP = 3;
    
    private static final int TASK_STATUS_SUCCESS = 3;
    private static final int TASK_STATUS_TERMINAL = 3;
    
    private static final String FUNCTION_NAME = "脚本修复";
    
    private static final String EVENT_AI_REPAIRED = "AI_REPAIRED";

    /** Behaviour-directive gate for this leg (factory default on). */
    public static final String KEY_DIRECTIVES_ENABLED = "ai.repair.script-repair-directives.enabled";

    /** Template family of this leg (tp_prompt_template name). */
    public static final String TEMPLATE_NAME = "script-repair-directives";

    /** Template business domain of this family (tp_prompt_template domain). */
    public static final String TEMPLATE_DOMAIN = "repair-script-loop";

    private final CoreGateway coreGateway;
    private final LlmFallbackChain llmFallbackChain;
    private final ProjectLlmRouter projectLlmRouter;
    private final RepairPromptTemplate promptTemplate;
    private final AiCallLogWriter aiCallLogWriter;
    private final ActionJsonValidator actionJsonValidator;
    private final ObjectMapper objectMapper;

    
    private int classifyWaitAttempts = 10;
    private long classifyWaitMillis = 3_000L;
    
    private int trialWaitAttempts = 150;
    private long trialWaitMillis = 2_000L;

    public ScriptRepairLoopServiceImpl(CoreGateway coreGateway, LlmFallbackChain llmFallbackChain,
                                       ProjectLlmRouter projectLlmRouter,
                                       RepairPromptTemplate promptTemplate,
                                       AiCallLogWriter aiCallLogWriter,
                                       ActionJsonValidator actionJsonValidator,
                                       ObjectMapper objectMapper) {
        this.coreGateway = coreGateway;
        this.llmFallbackChain = llmFallbackChain;
        this.projectLlmRouter = projectLlmRouter;
        this.promptTemplate = promptTemplate;
        this.aiCallLogWriter = aiCallLogWriter;
        this.actionJsonValidator = actionJsonValidator;
        this.objectMapper = objectMapper;
    }

    
    public void configureWaitsForTest(int classifyAttempts, long classifyMillis,
                                      int trialAttempts, long trialMillis) {
        this.classifyWaitAttempts = Math.max(1, classifyAttempts);
        this.classifyWaitMillis = Math.max(0L, classifyMillis);
        this.trialWaitAttempts = Math.max(1, trialAttempts);
        this.trialWaitMillis = Math.max(0L, trialMillis);
    }

    @Override
    public void repair(ExecFailedEventMessage message) {
        Long projectId = message.projectId();
        Long execTaskId = message.execTaskId();
        if (!SysConfigBooleans.on(coreGateway.getSysConfig(KEY_ENABLED))) {
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("execTaskId", execTaskId);
            before.put("caseId", message.caseId());
            coreGateway.writeAudit(new LlmAuditRequest(message.operatorId(), projectId,
                    "mq_consume", "exec-repair:task:" + execTaskId,
                    "REPAIR_GATE_SKIP", before, Map.of("action", "skip_repair_disabled")));
            return;
        }

        RepairTaskView source = awaitClassified(execTaskId);
        if (source == null) {
            record(projectId, message.caseId(), execTaskId, null, null, OUTCOME_GATE_SKIP,
                    "归因未完成，本轮不进修复环（等待窗口内 AI 分类列未落位）");
            return;
        }
        String errorDetail = message.execErrorDetail() != null && !message.execErrorDetail().isBlank()
                ? message.execErrorDetail() : source.errorDetail();
        if (errorDetail == null || errorDetail.isBlank()) {
            record(projectId, source.caseId(), execTaskId, null, null, OUTCOME_GATE_SKIP,
                    "失败详情为空无修复素材");
            return;
        }
        if (source.aiFailureClass() != CLASS_AI_LOCATOR) {
            record(projectId, source.caseId(), execTaskId, null, null, OUTCOME_GATE_SKIP,
                    source.aiFailureClass() == CLASS_PRODUCT_DEFECT
                            ? "产品缺陷类失败不进修复环（应提缺陷单）"
                            : "环境类失败不进修复环（应先修环境）");
            return;
        }
        long rounds = coreGateway.repairRounds(source.caseId());
        int limit = resolveRoundsLimit();
        if (rounds >= limit) {
            record(projectId, source.caseId(), execTaskId, null, null, OUTCOME_GATE_SKIP,
                    "轮次达上限（近 24 小时已修 " + rounds + " 轮/上限 " + limit + "），转人工");
            return;
        }

        RepairProposal proposal = buildProposal(message, source, errorDetail, projectId);
        if (!proposal.valid()) {
            record(projectId, source.caseId(), execTaskId, null, null, OUTCOME_GATE_SKIP,
                    "提案非法：" + proposal.invalidReason());
            return;
        }

        long trialTaskId;
        try {
            trialTaskId = coreGateway.createRepairTrialTask(execTaskId,
                    proposal.schemaVersion(), proposal.actionJson());
        } catch (Exception e) {
            log.warn("B4 试运行任务创建失败 taskId={} err={}", execTaskId, e.getMessage());
            record(projectId, source.caseId(), execTaskId, null, proposal.summary(),
                    OUTCOME_GATE_SKIP, "验证任务创建失败：" + e.getMessage());
            return;
        }
        RepairTaskView trial = awaitTerminal(trialTaskId);
        if (trial == null) {
            record(projectId, source.caseId(), execTaskId, trialTaskId, proposal.summary(),
                    OUTCOME_NOT_PASSED, "试运行未过：验证超时未到达终态，转人工");
            return;
        }
        if (trial.status() != TASK_STATUS_SUCCESS) {
            record(projectId, source.caseId(), execTaskId, trialTaskId, proposal.summary(),
                    OUTCOME_NOT_PASSED, "试运行未过：终态=" + statusLabel(trial.status())
                            + "，原脚本保持不动，转人工");
            return;
        }

        try {
            coreGateway.saveRepairedScript(source.caseId(), proposal.schemaVersion(),
                    proposal.actionJson());
        } catch (Exception e) {
            record(projectId, source.caseId(), execTaskId, trialTaskId, proposal.summary(),
                    OUTCOME_NOT_PASSED, "试运行未过：落版被脚本校验拒（" + e.getMessage()
                            + "），原脚本保持不动，转人工");
            return;
        }
        record(projectId, source.caseId(), execTaskId, trialTaskId, proposal.summary(),
                OUTCOME_PASSED, null);
        notifyTestOwner(projectId, source.caseId(), trialTaskId);
        log.info("B4 修复完成 caseId={} 源任务={} 试运行={} model={}",
                source.caseId(), execTaskId, trialTaskId, proposal.modelName());
    }

    
    private RepairTaskView awaitClassified(long execTaskId) {
        RepairTaskView last = null;
        for (int i = 0; i < classifyWaitAttempts; i++) {
            last = coreGateway.getRepairTaskView(execTaskId);
            if (last != null && last.aiFailureClass() != null) {
                return last;
            }
            sleep(classifyWaitMillis);
        }
        return last != null && last.aiFailureClass() != null ? last : null;
    }

    
    private RepairTaskView awaitTerminal(long trialTaskId) {
        for (int i = 0; i < trialWaitAttempts; i++) {
            RepairTaskView view = coreGateway.getRepairTaskView(trialTaskId);
            if (view != null && view.status() >= TASK_STATUS_TERMINAL) {
                return view;
            }
            sleep(trialWaitMillis);
        }
        return null;
    }

    /**
     * System head for one reasoning call through the shared L0 assembly:
     * governance gate first (closed = zero template reads), the published template
     * row when open, the built-in contract on any miss, and the failure-transparency
     * and evidence-first invariant appended exactly once.
     */
    String behaviorDirectivesHead() {
        return BehaviorKernelAssembly.assembleHeadFromGateway(coreGateway,
                KEY_DIRECTIVES_ENABLED, TEMPLATE_NAME, TEMPLATE_DOMAIN,
                RepairPromptTemplate.SYSTEM,
                "行为指令模板读路径不可用，本轮脚本修复提案回退内置契约");
    }

    
    private RepairProposal buildProposal(ExecFailedEventMessage message, RepairTaskView source,
                                   String errorDetail, Long projectId) {
        String model = coreGateway.getSysConfig(KEY_MODEL);
        if (model == null || model.isBlank()) {
            model = projectLlmRouter.route(projectId).modelName();
        }
        String visionImageUrl = resolveVisionImageUrl(message, projectId);
        LlmChatHandle handle = llmFallbackChain.chatHandle(
                ChatCompletionRequest.of(model, promptTemplate.messages(
                        behaviorDirectivesHead(), source.actionJson(), errorDetail, visionImageUrl)),
                projectId, null);
        LlmChatResult result = handle.result();
        aiCallLogWriter.insert(projectId, result.provider(), result.model(), FUNCTION_NAME,
                result.promptTokens(), result.completionTokens(), result.latencyMs());

        String extracted = JsonOutputExtractor.extractJson(result.content());
        JsonNode root;
        try {
            root = objectMapper.readTree(extracted);
        } catch (Exception e) {
            return RepairProposal.invalid("提案输出不可解析为 JSON");
        }
        if (root == null || !root.isObject()) {
            return RepairProposal.invalid("提案输出不是 JSON 对象");
        }
        String repairedJson = root.toString();
        ActionCheckResult check = actionJsonValidator.validate(repairedJson);
        if (!check.passed()) {
            return RepairProposal.invalid("动作 JSON 校验失败：" + String.join("；", check.errors()));
        }
        String schemaVersion = source.schemaVersion() == null || source.schemaVersion().isBlank()
                ? "v2" : source.schemaVersion();
        return RepairProposal.passed(repairedJson, schemaVersion, result.model(),
                "AI 修复提案（model=" + result.model() + "）已通过静态校验并试运行通过");
    }

    
    private String resolveVisionImageUrl(ExecFailedEventMessage message, Long projectId) {
        try {
            if (message.execScreenshotKey() == null || message.execScreenshotKey().isBlank()) {
                return null;
            }
            CoreGateway.ProjectBrief brief = coreGateway.getProject(projectId);
            if (brief == null || !Integer.valueOf(1).equals(brief.classifyVision())) {
                return null;
            }
            return coreGateway.screenshotUrl(message.execTaskId(), message.execScreenshotKey());
        } catch (Exception e) {
            log.warn("B4 视觉增强降级 taskId={}：截图签发失败回纯文本提案 err={}",
                    message.execTaskId(), e.getMessage());
            return null;
        }
    }

    
    private int resolveRoundsLimit() {
        String raw = coreGateway.getSysConfig(KEY_ROUNDS);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_ROUNDS;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed < 1) {
                log.warn("B4 轮次上限键值非法（{}）回退默认 {}", raw, DEFAULT_ROUNDS);
                return DEFAULT_ROUNDS;
            }
            return parsed;
        } catch (NumberFormatException e) {
            log.warn("B4 轮次上限键非数字（{}）回退默认 {}", raw, DEFAULT_ROUNDS);
            return DEFAULT_ROUNDS;
        }
    }

    
    private void record(Long projectId, Long caseId, Long sourceTaskId, Long trialTaskId,
                        String proposalSummary, int outcome, String note) {
        coreGateway.recordScriptRepair(projectId, caseId, sourceTaskId, trialTaskId,
                proposalSummary, outcome, note);
    }

    
    private void notifyTestOwner(Long projectId, long caseId, long trialTaskId) {
        try {
            CoreGateway.ProjectBrief brief = coreGateway.getProject(projectId);
            Long testOwnerId = brief == null ? null : brief.testOwnerId();
            if (testOwnerId == null) {
                log.warn("B4 修复完成通知跳过 caseId={}：项目未配置测试负责人", caseId);
                return;
            }
            coreGateway.createNotification(testOwnerId, projectId, EVENT_AI_REPAIRED,
                    "AI 已自动修复测试脚本",
                    "用例 #" + caseId + " 定位失败经 AI 修复并试运行验证通过，已生成脚本新版本"
                            + "（原版本可在版本线回退）。",
                    "ai-repaired:" + trialTaskId);
        } catch (Exception e) {
            log.warn("B4 修复完成通知写入失败 caseId={} err={}（落版结果不回滚）",
                    caseId, e.getMessage());
        }
    }

    private static String statusLabel(int status) {
        return switch (status) {
            case 3 -> "成功";
            case 4 -> "失败";
            case 5 -> "超时";
            case 6 -> "已跳过";
            default -> "未知(" + status + ")";
        };
    }

    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
