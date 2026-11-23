package com.sixbean.testpike.aiservice.locate.domain.service.impl;

import com.sixbean.testpike.common.pipeline.DenialGuidanceFrame;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.aichat.domain.component.ChatBehaviorDirectives;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.component.AiCallLogWriter;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelAssembly;
import com.sixbean.testpike.aiservice.locate.api.dto.VisualLocateRequest;
import com.sixbean.testpike.aiservice.locate.domain.component.VisualLocatePromptTemplate;
import com.sixbean.testpike.aiservice.locate.domain.service.VisualLocateService;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.JsonOutputExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigBooleans;


@Service
public class VisualLocateServiceImpl implements VisualLocateService {

    private static final Logger log = LoggerFactory.getLogger(VisualLocateServiceImpl.class);

 
    static final String KEY_VISION_ENABLED = "ai.vision.enabled";
 
    static final String KEY_LOCATE_ENABLED = "ai.vision.appauto-locate";
 
    static final String KEY_MODEL = "ai.vision.model";
    
    private static final String FUNCTION_NAME = "视觉定位兜底";

    /**
     * Factory-on governance key for the operator-tuned behaviour-directive
     * template read (same tri-state semantics as the dialog family: only an
     * explicit 0/false closes it; a missing row keeps directives on).
     */
    public static final String KEY_DIRECTIVES_ENABLED = "ai.vision.locate-directives.enabled";

    /** Template family of this domain (tp_prompt_template name). */
    public static final String TEMPLATE_NAME = "locate-directives";

    /** Template business domain of this family (tp_prompt_template domain). */
    public static final String TEMPLATE_DOMAIN = "visual-locate";

    private final CoreGateway coreGateway;
    private final LlmFallbackChain llmFallbackChain;
    private final AiCallLogWriter aiCallLogWriter;
    private final ObjectMapper objectMapper;
    private final VisualLocatePromptTemplate promptTemplate;

    public VisualLocateServiceImpl(CoreGateway coreGateway, LlmFallbackChain llmFallbackChain,
                                   AiCallLogWriter aiCallLogWriter, ObjectMapper objectMapper,
                                   VisualLocatePromptTemplate promptTemplate) {
        this.coreGateway = coreGateway;
        this.llmFallbackChain = llmFallbackChain;
        this.aiCallLogWriter = aiCallLogWriter;
        this.objectMapper = objectMapper;
        this.promptTemplate = promptTemplate;
    }

    @Override
    public Map<String, Object> locate(VisualLocateRequest request) {
        assertSwitchesOn();
        if (request.taskId() == null || request.projectId() == null) {
            throw new ServiceException("视觉定位请求缺少任务号或项目号，无法定位留痕与成本归属",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (request.screenshotB64() == null || request.screenshotB64().isBlank()) {
            throw new ServiceException("视觉定位缺少失败现场截图，无素材不烧模型，保持原样报错",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String model = coreGateway.getSysConfig(KEY_MODEL);
        if (model == null || model.isBlank()) {
            throw new ServiceException("视觉模型未配置（系统设置-" + KEY_MODEL
                    + "），视觉定位兜底不可用；不会改用文本模型假装视觉，保持原样报错",
                    BizErrorCode.SERVICE_UNAVAILABLE);
        }

        String dataUrl = "data:" + (request.screenshotMime() == null
                || request.screenshotMime().isBlank() ? "image/png" : request.screenshotMime())
                + ";base64," + request.screenshotB64();
        LlmChatHandle handle = llmFallbackChain.chatHandle(
                ChatCompletionRequest.of(model, promptTemplate.messages(request, dataUrl,
                        behaviorDirectivesHead())),
                request.projectId(), null);
        writeCallLog(request, handle);

        JsonNode parsed = parseStrict(request.taskId(), handle.result().content());
        double confidence = parsed.path("confidence").asDouble(0.0);
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("x", parsed.path("x").numberValue());
        point.put("y", parsed.path("y").numberValue());
        point.put("label", parsed.path("label").asText(""));
        point.put("confidence", confidence);
        log.info("视觉定位应答 taskId={} x={} y={} confidence={} model={}",
                request.taskId(), point.get("x"), point.get("y"), confidence,
                handle.result().model());
        return point;
    }

    
    private void assertSwitchesOn() {
        if (!SysConfigBooleans.on(coreGateway.getSysConfig(KEY_VISION_ENABLED))
                || !SysConfigBooleans.on(coreGateway.getSysConfig(KEY_LOCATE_ENABLED))) {
            throw new ServiceException(DenialGuidanceFrame.switchClosed("视觉定位兜底", KEY_VISION_ENABLED, KEY_LOCATE_ENABLED), BizErrorCode.FORBIDDEN);
        }
    }

    
    private JsonNode parseStrict(Long taskId, String content) {
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(JsonOutputExtractor.extractJson(content));
        } catch (Exception e) {
            throw new ServiceException("视觉模型返回的不是可解析的点位 JSON，无法采用（任务#"
                    + taskId + "），保持原样报错", BizErrorCode.AI_FORMAT_ERROR);
        }
        if (parsed == null || !parsed.isObject()
                || !parsed.path("x").isNumber() || !parsed.path("y").isNumber()) {
            throw new ServiceException("视觉模型未给出有效坐标（任务#" + taskId
                    + "），不采用猜测点位，保持原样报错", BizErrorCode.AI_FORMAT_ERROR);
        }
        return parsed;
    }

    private void writeCallLog(VisualLocateRequest request, LlmChatHandle handle) {
        aiCallLogWriter.insert(request.projectId(), handle.result().provider(),
                handle.result().model(), FUNCTION_NAME,
                handle.result().promptTokens(), handle.result().completionTokens(),
                handle.result().latencyMs());
    }

    /**
 * Behaviour-kernel head for one locate answer , R40: gate/
 * read/degrade sequence single-sourced in the assembly piece). A failing
 * read degrades to the built-in contract and never takes the locate answer
 * chain down (the coordinate contract itself stays enforced by the strict
 * parser above).
 */
    private String behaviorDirectivesHead() {
        return BehaviorKernelAssembly.assembleHead(
                coreGateway.getSysConfig(KEY_DIRECTIVES_ENABLED),
                () -> coreGateway.promptTemplateLatestByStatus(TEMPLATE_NAME,
                        TEMPLATE_DOMAIN, ChatBehaviorDirectives.PUBLISHED_STATUS),
                VisualLocatePromptTemplate.SYSTEM,
                "A2 行为指令模板读路径不可用，本轮定位回退内置契约");
    }
}
