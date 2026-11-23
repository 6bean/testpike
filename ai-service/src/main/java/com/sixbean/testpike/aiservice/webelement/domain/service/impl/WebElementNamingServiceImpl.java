package com.sixbean.testpike.aiservice.webelement.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.component.AiCallLogWriter;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigBooleans;
import com.sixbean.testpike.aiservice.taskstream.domain.component.TaskProcessEventStore;
import com.sixbean.testpike.aiservice.webelement.api.dto.SurveyCandidateDescriptor;
import com.sixbean.testpike.aiservice.webelement.api.dto.SurveyNamingRequest;
import com.sixbean.testpike.aiservice.webelement.api.dto.SurveyNamingResult;
import com.sixbean.testpike.aiservice.webelement.domain.service.WebElementNamingService;
import com.sixbean.testpike.common.util.JsonOutputExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Survey candidate naming (WE batch segment P3, ai-service face).
 * <p>One model round per surveyed page: the published template head (read per
 * call so a hot edit lands on the next survey) rides first, the deduped
 * candidate identities ride second. Every outcome is honest — switch off
 * burns nothing and writes nothing; a provider outage still leaves exactly
 * one ledger row so the attempt stays reconcilable; unparsable or out-of-band
 * output yields no names at all (zero fabrication: only keys the request
 * actually carried, only non-blank bounded values survive the filter).
 * <p>R62 segment b2: every attempted batch also lands exactly one naming frame
 * on the survey process stream (adopted / degraded / model-unset / provider
 * outage), counts included — an explicit switch-off keeps writing nothing,
 * and a request without a session id only skips the frame.
 */
@Service
public class WebElementNamingServiceImpl implements WebElementNamingService {

    private static final Logger log = LoggerFactory.getLogger(WebElementNamingServiceImpl.class);

    /** Naming master switch; a missing config row keeps naming on (factory-on tri-state). */
    static final String KEY_ENABLED = "ai.element.naming.enabled";
    /** Model name for the naming round; blank = nothing to burn with (honest skip). */
    static final String KEY_MODEL = "ai.element.naming.model";
    static final String TEMPLATE_NAME = "web-element-survey-naming";
    static final String TEMPLATE_DOMAIN = "web-element";
    static final int PUBLISHED_STATUS = 2;
    /** tp_ai_call_log function tag (human-readable domain name). */
    static final String FUNCTION = "页面元素测绘命名";
    /** Upper bound adopted from the request shape: a business name never needs more. */
    static final int MAX_NAME_CHARS = 40;
    /**
 * Process-stream kind of the survey tail (R62 segment b2). Same literal the
 * core face registers under AiTaskEventTrimSweeper.KIND_WEB_ELEMENT_SURVEY
 * — cross-module constant sharing would invert the dependency, and each
 * service already owns one event door (core TaskEventWriter / here
 * TaskProcessEventStore), so this stays a mirrored literal, not a third
 * writer (R40).
 */
    static final String EVENT_KIND = "web-element-survey";

    /**
     * Conservative built-in head used when no published template row exists
     * (or the template read itself fails): the prompt is never empty, and the
     * output-schema requirement below stays coupled to the parser above.
     */
    private static final String BUILT_IN_HEAD = """
            你是页面元素命名员。给定同一页面内的交互元素候选清单（key=定位锚身份，text=可见文本，\
            role/nearby=可选补充），为每个你能确定业务含义的候选起一个 12 字内的中文业务语义名。
            只输出一个 JSON 对象，不要输出其他文字，格式：{"names":[{"key":"原样回传的key","name":"业务语义名"}]}
            判断不了的候选直接省略；禁止编造名称；key 必须逐字原样回传。""";

    private final CoreGateway coreGateway;
    private final LlmFallbackChain llmFallbackChain;
    private final AiCallLogWriter aiCallLogWriter;
    private final TaskProcessEventStore processEvents;
    private final ObjectMapper objectMapper;

    public WebElementNamingServiceImpl(CoreGateway coreGateway, LlmFallbackChain llmFallbackChain,
                                       AiCallLogWriter aiCallLogWriter,
                                       TaskProcessEventStore processEvents,
                                       ObjectMapper objectMapper) {
        this.coreGateway = coreGateway;
        this.llmFallbackChain = llmFallbackChain;
        this.aiCallLogWriter = aiCallLogWriter;
        this.processEvents = processEvents;
        this.objectMapper = objectMapper;
    }

    @Override
    public SurveyNamingResult nameSurveyCandidates(SurveyNamingRequest request) {
        if (request == null || request.candidates() == null || request.candidates().isEmpty()) {
            return new SurveyNamingResult(false, Map.of());
        }
        String enabled = coreGateway.getSysConfig(KEY_ENABLED);
        if (enabled != null && !SysConfigBooleans.on(enabled)) {
            // Explicit off: zero model calls, zero ledger rows (factory-on keeps
            // a missing row meaning "still on", same tri-state as the kernel keys).
            return new SurveyNamingResult(false, Map.of());
        }
        String model = coreGateway.getSysConfig(KEY_MODEL);
        if (model == null || model.isBlank()) {
            log.warn("测绘命名未配置模型（{}），本轮跳过命名不烧调用 projectId={}",
                    KEY_MODEL, request.projectId());
            surveyFrame(request, "step", "命名未配置模型（" + KEY_MODEL + "），"
                    + request.candidates().size() + " 个候选保持规则采集名");
            return new SurveyNamingResult(false, Map.of());
        }
        LlmChatHandle handle;
        try {
            handle = llmFallbackChain.chatHandle(
                    ChatCompletionRequest.of(model, buildMessages(request)),
                    request.projectId(), null);
        } catch (Exception providerOutage) {
            // Failed attempt still lands one ledger row (reconcilable burns);
            // provider un-determined lands NULL honestly, tokens stay 0.
            aiCallLogWriter.insert(request.projectId(), null, model, FUNCTION,
                    0, 0, 0, null, "model_call_failed");
            log.warn("测绘命名模型调用失败，本轮不采用任何名字 projectId={}: {}",
                    request.projectId(), providerOutage.getMessage());
            surveyFrame(request, "error", "命名模型调用失败，本轮采用 0/"
                    + request.candidates().size() + "，候选保持规则采集名");
            return new SurveyNamingResult(false, Map.of());
        }
        aiCallLogWriter.insert(request.projectId(), handle.result().provider(),
                handle.result().model(), FUNCTION,
                handle.result().promptTokens(), handle.result().completionTokens(),
                handle.result().latencyMs());
        return parseNames(request, handle.result().content());
    }

    private List<ChatMessage> buildMessages(SurveyNamingRequest request) {
        StringBuilder user = new StringBuilder("页面：").append(request.pageUrl())
                .append("\n候选清单（JSON 数组，逐项含 key/text/role/nearby）：");
        try {
            List<Map<String, String>> rows = request.candidates().stream()
                    .map(WebElementNamingServiceImpl::descriptorRow)
                    .toList();
            user.append(objectMapper.writeValueAsString(rows));
        } catch (Exception serializationFailure) {
            for (SurveyCandidateDescriptor candidate : request.candidates()) {
                user.append('\n').append(candidate.key()).append(" | ")
                        .append(nullSafe(candidate.text()));
            }
        }
        return List.of(new ChatMessage("system", templateHead()),
                new ChatMessage("user", user.toString()));
    }

    private static Map<String, String> descriptorRow(SurveyCandidateDescriptor candidate) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("key", nullSafe(candidate.key()));
        row.put("text", nullSafe(candidate.text()));
        row.put("role", nullSafe(candidate.role()));
        row.put("nearby", nullSafe(candidate.nearby()));
        return row;
    }

    private String templateHead() {
        try {
            CoreGateway.PromptTemplateBrief brief = coreGateway.promptTemplateLatestByStatus(
                    TEMPLATE_NAME, TEMPLATE_DOMAIN, PUBLISHED_STATUS);
            if (brief != null && brief.content() != null && !brief.content().isBlank()) {
                return brief.content();
            }
        } catch (Exception templateReadFailure) {
            log.warn("测绘命名模板读取失败，改用内置命名指令继续: {}",
                    templateReadFailure.getMessage());
        }
        return BUILT_IN_HEAD;
    }

    private SurveyNamingResult parseNames(SurveyNamingRequest request, String content) {
        Set<String> knownKeys = new LinkedHashSet<>();
        for (SurveyCandidateDescriptor candidate : request.candidates()) {
            knownKeys.add(candidate.key());
        }
        Map<String, String> kept = new LinkedHashMap<>();
        try {
            JsonNode root = objectMapper.readTree(JsonOutputExtractor.extractJson(content));
            JsonNode names = root.path("names");
            if (names.isArray()) {
                for (JsonNode entry : names) {
                    String key = entry.path("key").asText("");
                    String name = entry.path("name").asText("").trim();
                    if (knownKeys.contains(key) && !name.isEmpty()
                            && name.length() <= MAX_NAME_CHARS) {
                        kept.put(key, name);
                    }
                }
            }
        } catch (Exception unparsable) {
            log.warn("测绘命名应答不是可解析 JSON，本轮零采用 projectId={}", request.projectId());
        }
        int total = request.candidates().size();
        surveyFrame(request, "step", kept.isEmpty()
                ? "命名应答无可采用项，本轮采用 0/" + total + "，候选保持规则采集名"
                : "命名采用 " + kept.size() + "/" + total + "（业务语义名已写入候选池）");
        return new SurveyNamingResult(!kept.isEmpty(), kept);
    }

    /**
 * One naming-batch frame per attempted batch on the survey stream
 * (R62 segment b2). The session id keys the same tail the core face
 * writes its step and terminal frames onto (job_ref = run_token = the
 * session id, single-tail contract); no session means no stream to
 * report onto, and the event door itself is already advisory-write.
 */
    private void surveyFrame(SurveyNamingRequest request, String level, String summary) {
        if (request.sessionId() == null) {
            return;
        }
        String sessionRef = String.valueOf(request.sessionId());
        processEvents.record(EVENT_KIND, sessionRef, sessionRef, level, summary, null);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
