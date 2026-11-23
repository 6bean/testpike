package com.sixbean.testpike.aiservice.classify.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.aichat.domain.component.ChatBehaviorDirectives;
import com.sixbean.testpike.aiservice.classify.domain.component.FailureClassifyPromptTemplate;
import com.sixbean.testpike.aiservice.classify.domain.service.FailureClassifyService;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.LlmAuditRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.AiCallLogWriter;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelAssembly;
import com.sixbean.testpike.common.mq.ExecFailedEventMessage;
import com.sixbean.testpike.common.util.JsonOutputExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigBooleans;


@Service
public class FailureClassifyServiceImpl implements FailureClassifyService {

    private static final Logger log = LoggerFactory.getLogger(FailureClassifyServiceImpl.class);

    
    static final String KEY_ENABLED = "ai.classify.enabled";
    
    static final String KEY_MODEL = "ai.classify.model";
    
    private static final int CLASS_MIN = 1;
    private static final int CLASS_MAX = 3;
    
    private static final String FUNCTION_NAME = "失败归因";

    /**
     * Factory-on governance key for the operator-tuned behaviour-directive
     * template read (same tri-state semantics as the dialog family: only an
     * explicit 0/false closes it; a missing row keeps directives on).
     */
    public static final String KEY_DIRECTIVES_ENABLED = "ai.classify.directives.enabled";

    /** Template family of this domain (tp_prompt_template name). */
    public static final String TEMPLATE_NAME = "classify-directives";

    /** Template business domain of this family (tp_prompt_template domain). */
    public static final String TEMPLATE_DOMAIN = "failure-classify";

    private final CoreGateway coreGateway;
    private final LlmFallbackChain llmFallbackChain;
    private final ProjectLlmRouter projectLlmRouter;
    private final AiCallLogWriter aiCallLogWriter;
    private final ObjectMapper objectMapper;
    private final FailureClassifyPromptTemplate promptTemplate;

    public FailureClassifyServiceImpl(CoreGateway coreGateway, LlmFallbackChain llmFallbackChain,
                                      ProjectLlmRouter projectLlmRouter,
                                      AiCallLogWriter aiCallLogWriter,
                                      ObjectMapper objectMapper,
                                      FailureClassifyPromptTemplate promptTemplate) {
        this.coreGateway = coreGateway;
        this.llmFallbackChain = llmFallbackChain;
        this.projectLlmRouter = projectLlmRouter;
        this.aiCallLogWriter = aiCallLogWriter;
        this.objectMapper = objectMapper;
        this.promptTemplate = promptTemplate;
    }

    @Override
    public void classify(ExecFailedEventMessage message) {
        Long projectId = message.projectId();
        if (!SysConfigBooleans.on(coreGateway.getSysConfig(KEY_ENABLED))) {
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("execTaskId", message.execTaskId());
            before.put("caseId", message.caseId());
            coreGateway.writeAudit(new LlmAuditRequest(message.operatorId(), projectId,
                    "mq_consume", "exec-classify:task:" + message.execTaskId(),
                    "CLASSIFY_GATE_SKIP", before, Map.of("action", "skip_classify_disabled")));
            return;
        }
        if (message.execErrorDetail() == null || message.execErrorDetail().isBlank()) {
            log.info("C3 归因跳过 taskId={}：失败详情原文为空，无判定素材", message.execTaskId());
            return;
        }

        Integer reused = findReusableClass(message);
        if (reused != null) {
            coreGateway.aiClassify(message.execTaskId(), reused,
                    "同类错误复用近 24 小时归类结果");
            log.info("C3 归因复用 taskId={} class={}（零 LLM 外呼）", message.execTaskId(), reused);
            return;
        }

        String model = coreGateway.getSysConfig(KEY_MODEL);
        if (model == null || model.isBlank()) {
            model = projectLlmRouter.route(projectId).modelName();
        }
        String visionImageUrl = resolveVisionImageUrl(message);
        LlmChatHandle handle = llmFallbackChain.chatHandle(
                ChatCompletionRequest.of(model, promptTemplate.messages(
                        message.execErrorType(), message.execErrorDetail(), visionImageUrl,
                        behaviorDirectivesHead())),
                projectId, null);
        LlmChatResult result = handle.result();

        JsonNode parsed = parseContract(result.content());
        int classification = parsed == null ? 0 : parsed.path("classification").asInt(0);
        if (classification < CLASS_MIN || classification > CLASS_MAX) {
            log.warn("C3 归因输出值域外 taskId={} 零写回 content={}",
                    message.execTaskId(), abbreviate(result.content()));
            writeCallLog(projectId, message, result);
            return;
        }
        String note = parsed.path("note").asText("");
        coreGateway.aiClassify(message.execTaskId(), classification, note);
        writeCallLog(projectId, message, result);
        log.info("C3 归因完成 taskId={} class={} model={}",
                message.execTaskId(), classification, result.model());
    }

    
    private String resolveVisionImageUrl(ExecFailedEventMessage message) {
        try {
            if (message.execScreenshotKey() == null || message.execScreenshotKey().isBlank()) {
                return null;
            }
            CoreGateway.ProjectBrief brief = coreGateway.getProject(message.projectId());
            if (brief == null || !Integer.valueOf(1).equals(brief.classifyVision())) {
                return null;
            }
            return coreGateway.screenshotUrl(message.execTaskId(), message.execScreenshotKey());
        } catch (Exception e) {
            log.warn("C3 视觉增强降级 taskId={}：截图签发失败回纯文本归因 err={}",
                    message.execTaskId(), e.getMessage());
            return null;
        }
    }

    
    private Integer findReusableClass(ExecFailedEventMessage message) {
        if (message.caseId() == null) {
            return null;
        }
        String digest = md5(message.execErrorDetail());
        List<Map<String, Object>> recent;
        try {
            recent = coreGateway.listRecentClassify(message.caseId());
        } catch (Exception e) {
            log.warn("C3 近期归类读路径失败 taskId={} err={}（降级为新归类）",
                    message.execTaskId(), e.getMessage());
            return null;
        }
        for (Map<String, Object> row : recent) {
            Object detail = row.get("error_detail");
            if (detail != null && digest.equals(md5(String.valueOf(detail)))) {
                Object old = row.get("ai_failure_class");
                if (old instanceof Number n && n.intValue() >= CLASS_MIN && n.intValue() <= CLASS_MAX) {
                    return n.intValue();
                }
            }
        }
        return null;
    }

    
    private JsonNode parseContract(String content) {
        try {
            return objectMapper.readTree(JsonOutputExtractor.extractJson(content));
        } catch (Exception e) {
            return null;
        }
    }

    
    private void writeCallLog(Long projectId, ExecFailedEventMessage message, LlmChatResult result) {
        aiCallLogWriter.insert(projectId, result.provider(), result.model(), FUNCTION_NAME,
                result.promptTokens(), result.completionTokens(), result.latencyMs());
    }

    private static String md5(String text) {
        return DigestUtils.md5DigestAsHex(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }

    /**
 * Behaviour-kernel head for one classification run , R40:
 * gate/read/degrade sequence single-sourced in the assembly piece). Fresh
 * reads per run (uncached, so a template edit lands on the very next
 * classification); a failing read degrades to the built-in contract and
 * never takes the classification chain down.
 */
    private String behaviorDirectivesHead() {
        return BehaviorKernelAssembly.assembleHead(
                coreGateway.getSysConfig(KEY_DIRECTIVES_ENABLED),
                () -> coreGateway.promptTemplateLatestByStatus(TEMPLATE_NAME,
                        TEMPLATE_DOMAIN, ChatBehaviorDirectives.PUBLISHED_STATUS),
                FailureClassifyPromptTemplate.SYSTEM,
                "C3 行为指令模板读路径不可用，本轮归因回退内置契约");
    }
}
