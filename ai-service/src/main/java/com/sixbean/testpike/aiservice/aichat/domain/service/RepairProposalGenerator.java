package com.sixbean.testpike.aiservice.aichat.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.aichat.domain.component.ChatRepairDraft;
import com.sixbean.testpike.aiservice.aichat.infrastructure.remote.ScriptView;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.AiCallLogWriter;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelAssembly;
import com.sixbean.testpike.aiservice.repair.domain.component.RepairPromptTemplate;
import com.sixbean.testpike.aiservice.repair.domain.service.impl.ScriptRepairLoopServiceImpl;
import com.sixbean.testpike.common.util.ActionCheckResult;
import com.sixbean.testpike.common.util.ActionJsonValidator;
import com.sixbean.testpike.common.util.JsonOutputExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Dialog-side repair proposal kernel : given a
 * failure diagnosis that is on the table, asks the model for a repaired
 * action script and gates it through the SAME single-source pieces the B4
 * auto-repair loop uses — {@link RepairPromptTemplate} (one prompt vocabulary,
 * never a second one), {@link JsonOutputExtractor} + {@link
 * ActionJsonValidator} (fence-tolerant parse and schema gate).
 * <p>Deliberate scope cut versus B4: proposal half-chain only. The B4 loop
 * additionally does rounds accounting, trial runs and version landing — the
 * dialog surface lands nothing by itself (a draft card waits for a confirm
 * token in knife 3b), so reusing the auto loop wholesale would smuggle
 * writes past the confirmation red line.
 * <p>Cost honesty: every outbound call is journaled to tp_ai_call_log under
 * its own function label, out-of-range answers included — observation stays
 * in-ledger (no off-book token burn), same posture as B4.
 */
@Component
public class RepairProposalGenerator {

    private static final Logger log = LoggerFactory.getLogger(RepairProposalGenerator.class);

    /** Repair model key, shared with B4 (one key, two consumers, no fork). */
    static final String KEY_MODEL = "ai.repair.model";
    
    private static final String FUNCTION_NAME = "对话修复提案";

    private final LlmFallbackChain llmFallbackChain;
    private final ProjectLlmRouter projectLlmRouter;
    private final CoreGateway coreGateway;
    private final RepairPromptTemplate promptTemplate;
    private final ActionJsonValidator actionJsonValidator;
    private final AiCallLogWriter aiCallLogWriter;
    private final ObjectMapper objectMapper;

    public RepairProposalGenerator(LlmFallbackChain llmFallbackChain,
                                   ProjectLlmRouter projectLlmRouter,
                                   CoreGateway coreGateway,
                                   RepairPromptTemplate promptTemplate,
                                   ActionJsonValidator actionJsonValidator,
                                   AiCallLogWriter aiCallLogWriter,
                                   ObjectMapper objectMapper) {
        this.llmFallbackChain = llmFallbackChain;
        this.projectLlmRouter = projectLlmRouter;
        this.coreGateway = coreGateway;
        this.promptTemplate = promptTemplate;
        this.actionJsonValidator = actionJsonValidator;
        this.aiCallLogWriter = aiCallLogWriter;
        this.objectMapper = objectMapper;
    }

    /**
     * Behaviour-kernel head for one repair-draft call (R40 single source: the
     * gate → published-row → degrade sequence lives in BehaviorKernelAssembly).
     * Shares the B4 vocabulary — same governance key, same template family —
     * so one operator edit on the Prompt Template page benefits both repair
     * legs. Fresh read per call; a closed gate performs zero template reads;
     * a failing read degrades to the built-in contract and never takes the
     * draft run down.
     */
    String behaviorDirectivesHead() {
        return BehaviorKernelAssembly.assembleHeadFromGateway(coreGateway,
                ScriptRepairLoopServiceImpl.KEY_DIRECTIVES_ENABLED,
                ScriptRepairLoopServiceImpl.TEMPLATE_NAME,
                ScriptRepairLoopServiceImpl.TEMPLATE_DOMAIN,
                RepairPromptTemplate.SYSTEM,
                "对话修复草案行为指令模板读取失败，本轮修复提案回退内置契约");
    }

    /**
     * Build one draft for a diagnosed failure: read the CURRENT script (the
     * diff "before" and the optimistic-lock baseVersion), ask for a repair,
     * gate the answer. Any untrusted outcome is a rejected draft with a plain
     * reason — the caller then journals nothing (honest absence, never a
     * half-baked card). A remote leg failing degrades the same way.
     */
    public ChatRepairDraft generate(long projectId, long caseId, String errorDetail) {
        ScriptView script;
        try {
            script = coreGateway.getScript(caseId);
        } catch (RuntimeException unreadable) {
            log.warn("Chat repair proposal skipped: script read failed caseId={} err={}", caseId,
                    unreadable.getMessage());
            return ChatRepairDraft.rejected("读取当前脚本失败，无法给出可信修复草案");
        }
        if (script == null || script.actionJson() == null || script.actionJson().isBlank()) {
            // When the row was read but empty, the version anchor is known
            // and must survive into the refusal journal (audit duty).
            return script == null
                    ? ChatRepairDraft.rejected("当前脚本为空，无修复素材")
                    : ChatRepairDraft.rejected("当前脚本为空，无修复素材", script.version());
        }
        String model = coreGateway.getSysConfig(KEY_MODEL);
        if (model == null || model.isBlank()) {
            model = projectLlmRouter.route(projectId).modelName();
        }
        LlmChatHandle handle;
        try {
            handle = llmFallbackChain.chatHandle(
                    ChatCompletionRequest.of(model, promptTemplate.messages(
                            behaviorDirectivesHead(), script.actionJson(), errorDetail, null)),
                    projectId, null);
        } catch (RuntimeException providerDown) {
            log.warn("Chat repair proposal provider call failed projectId={} err={}", projectId,
                    providerDown.getMessage());
            return ChatRepairDraft.rejected("修复提案服务暂时不可用", script.version());
        }
        LlmChatResult result = handle.result();
        aiCallLogWriter.insert(projectId, result.provider(), result.model(), FUNCTION_NAME,
                result.promptTokens(), result.completionTokens(), result.latencyMs());

        String extracted = JsonOutputExtractor.extractJson(result.content());
        JsonNode root;
        try {
            root = objectMapper.readTree(extracted);
        } catch (Exception unparsable) {
            return ChatRepairDraft.rejected("提案输出不可解析为 JSON", script.version());
        }
        if (root == null || !root.isObject()) {
            return ChatRepairDraft.rejected("提案输出不是 JSON 对象", script.version());
        }
        String repairedJson = root.toString();
        ActionCheckResult check = actionJsonValidator.validate(repairedJson);
        if (!check.passed()) {
            return ChatRepairDraft.rejected("动作 JSON 校验失败："
                    + String.join("；", check.errors()), script.version());
        }
        String schemaVersion = script.schemaVersion() == null || script.schemaVersion().isBlank()
                ? ActionJsonValidator.ACTION_JSON_VERSION : script.schemaVersion();
        return ChatRepairDraft.proposed(repairedJson, schemaVersion, result.model(),
                "AI 修复草案（model=" + result.model()
                        + "）已通过静态校验，确认后才会写入脚本新版本",
                caseId, script.version());
    }
}
