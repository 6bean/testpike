package com.sixbean.testpike.aiservice.perfdraft.domain.service.impl;

import com.sixbean.testpike.common.pipeline.DenialGuidanceFrame;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.aichat.domain.component.ChatBehaviorDirectives;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelAssembly;
import com.sixbean.testpike.aiservice.llm.domain.component.AiCallLogWriter;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.perfdraft.api.dto.PerfDraftRequest;
import com.sixbean.testpike.aiservice.perfdraft.domain.component.PerfDraftPromptTemplate;
import com.sixbean.testpike.aiservice.perfdraft.domain.service.PerfDraftService;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.JsonOutputExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Load-test scenario drafting answer implementation (B-11; the model-side
 * single hop of the core orchestration leg).
 * <p>Flow: second-line switch check (core PerfAiSwitchGate has already judged
 * the request once on the normal path; this check only defends a bare hit on
 * the internal door — acceptance is identical: only an explicit "true" opens
 * the point, missing/blank/dirty rows keep it closed and cost nothing) →
 * plain-word parameter checks (project anchor, description) → project LLM
 * routing (ProjectLlmRouter, no private config) → one LlmFallbackChain hop →
 * JsonOutputExtractor fence-tolerant parse of {"steps":[...]}.
 * <p>No validation here beyond parsing: step-shape verdicts (action/method/
 * url domain, engine compile) belong to core PerfStepActionGate — a second
 * spec in this leg is forbidden (red line 6). The draft is returned as-is for
 * the wizard prefill; nothing is written to tp_perf_scenario from this path,
 * saving always goes through the human-confirmed scenario door.
 * <p>Failure semantics: unparseable reply or missing/non-array steps =
 * AI_FORMAT_ERROR in plain words (never a guessed structure); every model hop
 * is accounted in tp_ai_call_log under the request's project (cost hangs on
 * AiCallLogWriter, same as the mock-gen precedent).
 */
@Service
public class PerfDraftServiceImpl implements PerfDraftService {

    private static final Logger log = LoggerFactory.getLogger(PerfDraftServiceImpl.class);

    /** Drafting switch key (K8 row seeded by migration; core owns the point matrix). */
    static final String KEY_SCENARIO_DRAFT = "ai.perf.scenario-draft";

    /** Human-readable function label on tp_ai_call_log rows. */
    private static final String FUNCTION_NAME = "压测场景草拟";

    /**
 * Governance switch of the behaviour-directive family for this leg (
 * per-domain upgrade): only an explicit 0/false closes the template read;
 * a missing row keeps directives on, same tri-state as the dialog family.
 */
    public static final String KEY_DIRECTIVES_ENABLED = "ai.perf.scenario-draft-directives.enabled";

    /** Template family of this leg (tp_prompt_template name). */
    public static final String TEMPLATE_NAME = "perf-draft-directives";

    /** Template business domain of this family (tp_prompt_template domain). */
    public static final String TEMPLATE_DOMAIN = "perf-scenario-draft";

    /**
     * Behaviour-kernel head for one drafting call (R40 single source: gate,
     * read and degrade sequence live in BehaviorKernelAssembly). Fresh read per
     * call; a closed gate performs zero template reads; a failing read degrades
     * to the built-in contract and never takes the drafting run down.
     */
    String behaviorDirectivesHead() {
        return BehaviorKernelAssembly.assembleHeadFromGateway(coreGateway,
                KEY_DIRECTIVES_ENABLED, TEMPLATE_NAME, TEMPLATE_DOMAIN,
                PerfDraftPromptTemplate.SYSTEM,
                "行为指令模板读路径不可用，本轮压测草拟回退内置契约");
    }

    private final CoreGateway coreGateway;
    private final LlmFallbackChain llmFallbackChain;
    private final ProjectLlmRouter projectLlmRouter;
    private final AiCallLogWriter aiCallLogWriter;
    private final ObjectMapper objectMapper;
    private final PerfDraftPromptTemplate promptTemplate;

    public PerfDraftServiceImpl(CoreGateway coreGateway, LlmFallbackChain llmFallbackChain,
                                ProjectLlmRouter projectLlmRouter, AiCallLogWriter aiCallLogWriter,
                                ObjectMapper objectMapper, PerfDraftPromptTemplate promptTemplate) {
        this.coreGateway = coreGateway;
        this.llmFallbackChain = llmFallbackChain;
        this.projectLlmRouter = projectLlmRouter;
        this.aiCallLogWriter = aiCallLogWriter;
        this.objectMapper = objectMapper;
        this.promptTemplate = promptTemplate;
    }

    @Override
    public Map<String, Object> draft(PerfDraftRequest request) {
        assertSwitchOn();
        if (request.projectId() == null) {
            throw new ServiceException("压测场景草拟请求缺少项目号，无法路由模型与成本归属",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (request.description() == null || request.description().isBlank()) {
            throw new ServiceException("压测场景草拟缺少压测意图描述，无素材不烧模型",
                    BizErrorCode.VALIDATION_ERROR);
        }

        String model = projectLlmRouter.route(request.projectId()).modelName();
        LlmChatHandle handle = llmFallbackChain.chatHandle(
                ChatCompletionRequest.of(model, promptTemplate.messages(request, behaviorDirectivesHead())),
                request.projectId(), null);
        writeCallLog(request, handle);

        List<Map<String, Object>> steps = parseSteps(handle.result().content());
        log.info("压测场景草拟应答 projectId={} steps={} model={}",
                request.projectId(), steps.size(), handle.result().model());
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("steps", steps);
        return draft;
    }

    /**
     * Bare-hit defense: anything but an explicit "true" refuses in plain words
     * naming the exact key, with zero model calls (same acceptance as core
     * PerfAiSwitchGate — a dirty row can never switch an AI point on).
     */
    private void assertSwitchOn() {
        String value = coreGateway.getSysConfig(KEY_SCENARIO_DRAFT);
        if (value == null || !"true".equalsIgnoreCase(value.trim())) {
            throw new ServiceException(DenialGuidanceFrame.switchClosed("压测 AI 草拟场景", KEY_SCENARIO_DRAFT), BizErrorCode.FORBIDDEN);
        }
    }

    /**
     * Strict parse: after fence stripping the reply must carry a steps array
     * of JSON objects; numbers/strings stay native (no second translation).
     * Anything else = AI_FORMAT_ERROR in plain words.
     */
    private List<Map<String, Object>> parseSteps(String content) {
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(JsonOutputExtractor.extractJson(content));
        } catch (Exception e) {
            throw new ServiceException("模型返回的不是可解析的压测步骤 JSON，无法采用（请把压测意图"
                    + "写得更具体一些：接口路径+动作），或稍后重试", BizErrorCode.AI_FORMAT_ERROR);
        }
        JsonNode stepsNode = parsed == null ? null : parsed.path("steps");
        if (stepsNode == null || !stepsNode.isArray()) {
            throw new ServiceException("模型应答缺少 steps 步骤数组（契约结构不符），本次草拟不采用",
                    BizErrorCode.AI_FORMAT_ERROR);
        }
        List<Map<String, Object>> steps = objectMapper.convertValue(stepsNode,
                objectMapper.getTypeFactory().constructCollectionType(
                        java.util.ArrayList.class,
                        objectMapper.getTypeFactory().constructMapType(
                                java.util.LinkedHashMap.class, String.class, Object.class)));
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i) == null) {
                throw new ServiceException("模型应答第 " + (i + 1) + " 步不是 JSON 对象，"
                        + "步形不完整不采用", BizErrorCode.AI_FORMAT_ERROR);
            }
        }
        return steps;
    }

    private void writeCallLog(PerfDraftRequest request, LlmChatHandle handle) {
        aiCallLogWriter.insert(request.projectId(), handle.result().provider(),
                handle.result().model(), FUNCTION_NAME,
                handle.result().promptTokens(), handle.result().completionTokens(),
                handle.result().latencyMs());
    }
}
