package com.sixbean.testpike.aiservice.perfinsight.domain.service.impl;

import com.sixbean.testpike.common.pipeline.DenialGuidanceFrame;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.component.AiCallLogWriter;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelAssembly;
import com.sixbean.testpike.aiservice.perfinsight.api.dto.PerfInsightRequest;
import com.sixbean.testpike.aiservice.perfinsight.domain.component.PerfInsightPromptTemplate;
import com.sixbean.testpike.aiservice.perfinsight.domain.service.PerfInsightService;
import com.sixbean.testpike.aiservice.taskstream.domain.component.TaskFailureEvent;
import com.sixbean.testpike.aiservice.taskstream.domain.component.TaskFailureEventFormatter;
import com.sixbean.testpike.aiservice.taskstream.domain.component.TaskProcessEventStore;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.JsonOutputExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Load-test run-insight read-out answer implementation (B-11 ; the
 * model-side single hop of the core orchestration leg, PerfDraftServiceImpl
 * family posture).
 * <p>Flow: second-line switch check (core PerfAiSwitchGate has already judged
 * the request once on the normal path; this check only defends a bare hit on
 * the internal door — acceptance is identical: only an explicit "true" opens
 * the point, missing/blank/dirty rows keep it closed and cost nothing) →
 * plain-word parameter checks (project anchor, facts block) → project LLM
 * routing (ProjectLlmRouter, no private config) → one LlmFallbackChain hop →
 * JsonOutputExtractor fence-tolerant parse of {"insight":"..."}.
 * <p>No attribution math here: the numbers ride the prompt verbatim from core's
 * single source (buckets/error-class TopN/bottleneck hints) — a second
 * recomputation in this leg is forbidden (red line 6). The read-out is a
 * transient card text; it never rewrites the pass/fail verdict and nothing is
 * persisted except the call-log cost row.
 * <p>Failure semantics: unparseable reply, missing/non-text insight or a blank
 * paragraph = AI_FORMAT_ERROR in plain words (never a guessed read-out); every
 * model hop is accounted in tp_ai_call_log under the request's project.
 */
@Service
public class PerfInsightServiceImpl implements PerfInsightService {

    private static final Logger log = LoggerFactory.getLogger(PerfInsightServiceImpl.class);

    /** Insight switch key (K8 row seeded by migration; core owns the point matrix). */
    static final String KEY_INSIGHT = "ai.perf.insight";

    /** Human-readable function label on tp_ai_call_log rows. */
    private static final String FUNCTION_NAME = "压测归因解读";

    /** Governance key: operator-tuned directive head for the read-out leg. */
    public static final String KEY_DIRECTIVES_ENABLED = "ai.perf.insight-directives.enabled";

    /** Template family of this leg (tp_prompt_template name). */
    public static final String TEMPLATE_NAME = "perf-insight-directives";

    /** Template business domain of this family (tp_prompt_template domain). */
    public static final String TEMPLATE_DOMAIN = "perf-insight-readout";

    /** Closed job_kind literal of this leg in the shared process-event ledger
 * (tp_ai_task_event, ): job_ref = run_token = the load-test run id. */
    static final String PROCESS_JOB_KIND = "perfinsight";

    /** Retry affordance named on every terminal failure line of this leg. */
    private static final String RETRY_ENTRY = "压力测试报告页「AI 归因解读」按钮";

    /** event door: optional seam (legacy wiring without the store =
 * zero-touch no-op, same posture as the explore / steps-gen families). */
    private TaskProcessEventStore processEvents;

    @Autowired(required = false)
    public void setProcessEvents(TaskProcessEventStore store) {
        this.processEvents = store;
    }

    private final CoreGateway coreGateway;
    private final LlmFallbackChain llmFallbackChain;
    private final ProjectLlmRouter projectLlmRouter;
    private final AiCallLogWriter aiCallLogWriter;
    private final ObjectMapper objectMapper;
    private final PerfInsightPromptTemplate promptTemplate;

    public PerfInsightServiceImpl(CoreGateway coreGateway, LlmFallbackChain llmFallbackChain,
                                  ProjectLlmRouter projectLlmRouter, AiCallLogWriter aiCallLogWriter,
                                  ObjectMapper objectMapper, PerfInsightPromptTemplate promptTemplate) {
        this.coreGateway = coreGateway;
        this.llmFallbackChain = llmFallbackChain;
        this.projectLlmRouter = projectLlmRouter;
        this.aiCallLogWriter = aiCallLogWriter;
        this.objectMapper = objectMapper;
        this.promptTemplate = promptTemplate;
    }

    @Override
    public Map<String, Object> explain(PerfInsightRequest request) {
        // tail-f: an anchored read-out is one observed attempt — the whole
        // leg runs inside the try so even a switch or parameter refusal ends
        // with the shaped failure line (: failure must feed back, never a
        // silent stop). Anchorless legacy calls record nothing.
        String anchor = request.runId() == null ? null : String.valueOf(request.runId());
        try {
            recordStep(anchor, "开始压测归因解读（运行 #" + anchor + "）");
            assertSwitchOn();
            if (request.projectId() == null) {
                throw new ServiceException("压测归因解读请求缺少项目号，无法路由模型与成本归属",
                        BizErrorCode.VALIDATION_ERROR);
            }
            if (request.factsBlock() == null || request.factsBlock().isBlank()) {
                throw new ServiceException("压测归因解读缺少归因事实数据，无素材不烧模型",
                        BizErrorCode.VALIDATION_ERROR);
            }

            String model = projectLlmRouter.route(request.projectId()).modelName();
            LlmChatHandle handle = llmFallbackChain.chatHandle(
                    ChatCompletionRequest.of(model, promptTemplate.messages(request, directivesHead())),
                    request.projectId(), null);
            writeCallLog(request, handle);
            recordThinking(anchor, handle);

            String insight = parseInsight(handle.result().content());
            recordStep(anchor, "压测归因解读完成（正文 " + insight.length() + " 字，模型 "
                    + handle.result().model() + "，耗时 " + handle.result().latencyMs() + "ms）");
            log.info("压测归因解读应答 projectId={} insightChars={} model={}",
                    request.projectId(), insight.length(), handle.result().model());
            Map<String, Object> readOut = new LinkedHashMap<>();
            readOut.put("insight", insight);
            return readOut;
        } catch (RuntimeException failure) {
            recordTerminalFailure(anchor, failure);
            throw failure;
        }
    }

    /**
     * Behaviour-kernel head for one read-out call (R40 single source: the
     * gate → published-row → degrade sequence lives in BehaviorKernelAssembly).
     * Fresh read per call; a closed gate performs zero template reads; a
     * failing read degrades to the built-in contract and never takes the
     * read-out down.
     */
    String directivesHead() {
        return BehaviorKernelAssembly.assembleHeadFromGateway(coreGateway,
                KEY_DIRECTIVES_ENABLED, TEMPLATE_NAME, TEMPLATE_DOMAIN,
                PerfInsightPromptTemplate.SYSTEM,
                "压测归因解读行为指令模板读取失败，本轮解读回退内置契约");
    }

    /**
     * Bare-hit defense: anything but an explicit "true" refuses in plain words
     * naming the exact key, with zero model calls (same acceptance as core
     * PerfAiSwitchGate — a dirty row can never switch an AI point on).
     */
    private void assertSwitchOn() {
        String value = coreGateway.getSysConfig(KEY_INSIGHT);
        if (value == null || !"true".equalsIgnoreCase(value.trim())) {
            throw new ServiceException(DenialGuidanceFrame.switchClosed("压测 AI 归因解读", KEY_INSIGHT), BizErrorCode.FORBIDDEN);
        }
    }

    /**
     * Strict parse: after fence stripping the reply must carry a non-blank
     * string insight; anything else = AI_FORMAT_ERROR in plain words.
     */
    private String parseInsight(String content) {
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(JsonOutputExtractor.extractJson(content));
        } catch (Exception e) {
            throw new ServiceException("模型返回的不是可解析的解读 JSON，无法采用（可稍后重试或"
                    + "先直接查看归因数据本身）", BizErrorCode.AI_FORMAT_ERROR);
        }
        JsonNode insightNode = parsed == null ? null : parsed.path("insight");
        if (insightNode == null || !insightNode.isTextual()) {
            throw new ServiceException("模型应答缺少 insight 解读文本（契约结构不符），本次解读不采用",
                    BizErrorCode.AI_FORMAT_ERROR);
        }
        String insight = insightNode.asText().trim();
        if (insight.isEmpty()) {
            throw new ServiceException("模型应答的 insight 为空文本，无解读内容不采用",
                    BizErrorCode.AI_FORMAT_ERROR);
        }
        return insight;
    }

    private void writeCallLog(PerfInsightRequest request, LlmChatHandle handle) {
        aiCallLogWriter.insert(request.projectId(), handle.result().provider(),
                handle.result().model(), FUNCTION_NAME,
                handle.result().promptTokens(), handle.result().completionTokens(),
                handle.result().latencyMs());
    }

    /** Append one step-level event of an anchored run. Anchorless call or an
     *  uninstalled seam = zero-touch; the store door itself fail-softs write
     *  outages, so no extra try/catch layer is added here (R40 posture of the
     *  steps-gen recordProcessEvent seam). */
    private void recordStep(String anchor, String summary) {
        if (anchor == null || processEvents == null) {
            return;
        }
        processEvents.record(PROCESS_JOB_KIND, anchor, anchor, "step", summary, null);
    }

    /** The model hop's visible outcome as a thinking-level event: human line
     *  with the token counts the drawer reads at a glance, machine face in the
     *  detail JSON (same fields, keyable by the viewer without parsing prose). */
    private void recordThinking(String anchor, LlmChatHandle handle) {
        if (anchor == null || processEvents == null) {
            return;
        }
        String detailJson;
        try {
            detailJson = objectMapper.writeValueAsString(Map.of(
                    "model", String.valueOf(handle.result().model()),
                    "promptTokens", handle.result().promptTokens(),
                    "completionTokens", handle.result().completionTokens(),
                    "latencyMs", handle.result().latencyMs()));
        } catch (Exception malformed) {
            detailJson = "{}";
        }
        processEvents.record(PROCESS_JOB_KIND, anchor, anchor, "thinking",
                "模型应答已取回（输入 " + handle.result().promptTokens() + " tokens，输出 "
                        + handle.result().completionTokens() + " tokens，耗时 "
                        + handle.result().latencyMs() + "ms）", detailJson);
    }

    /** End an anchored run that died on {@code failure} with the single-source
     *  shaped line (category + suggestion + retry entry — no second sentence
     *  spec here, red line 6), then let the exception propagate unchanged. */
    private void recordTerminalFailure(String anchor, RuntimeException failure) {
        if (anchor == null || processEvents == null) {
            return;
        }
        TaskFailureEvent terminal = TaskFailureEventFormatter.format(failure, RETRY_ENTRY);
        processEvents.record(PROCESS_JOB_KIND, anchor, anchor, "error",
                terminal.summary(), terminal.detailJson());
    }
}
