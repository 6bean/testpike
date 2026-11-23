package com.sixbean.testpike.aiservice.popuplearn.domain.service.impl;

import com.sixbean.testpike.common.pipeline.DenialGuidanceFrame;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.aichat.domain.component.ChatBehaviorDirectives;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelAssembly;
import com.sixbean.testpike.aiservice.llm.domain.component.AiCallLogWriter;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.popuplearn.api.dto.PopupLearnRequest;
import com.sixbean.testpike.aiservice.popuplearn.domain.component.PopupLearnPromptTemplate;
import com.sixbean.testpike.aiservice.popuplearn.domain.service.PopupLearnService;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.JsonOutputExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigBooleans;


@Service
public class PopupLearnServiceImpl implements PopupLearnService {

    private static final Logger log = LoggerFactory.getLogger(PopupLearnServiceImpl.class);

 
    static final String KEY_VISION_ENABLED = "ai.vision.enabled";
 
    static final String KEY_LEARN_ENABLED = "ai.appauto.popup-rule-learn";
 
    static final String KEY_MODEL = "ai.vision.model";
    
    private static final String FUNCTION_NAME = "弹窗规则学习";

    /**
 * Governance switch of the behaviour-directive family for this leg (
 * per-domain upgrade): only an explicit 0/false closes the template read;
 * a missing row keeps directives on, same tri-state as the dialog family.
 */
    public static final String KEY_DIRECTIVES_ENABLED = "ai.appauto.popup-learn-directives.enabled";

    /** Template family of this leg (tp_prompt_template name). */
    public static final String TEMPLATE_NAME = "popup-learn-directives";

    /** Template business domain of this family (tp_prompt_template domain). */
    public static final String TEMPLATE_DOMAIN = "popup-rule-learn";

    /**
     * Behaviour-kernel head for one learning call (R40 single source: gate,
     * read and degrade sequence live in BehaviorKernelAssembly). Fresh read per
     * call; a closed gate performs zero template reads; a failing read degrades
     * to the built-in contract and never takes the learning run down.
     */
    String behaviorDirectivesHead() {
        return BehaviorKernelAssembly.assembleHeadFromGateway(coreGateway,
                KEY_DIRECTIVES_ENABLED, TEMPLATE_NAME, TEMPLATE_DOMAIN,
                PopupLearnPromptTemplate.SYSTEM,
                "行为指令模板读路径不可用，本轮弹窗学习回退内置契约");
    }

    private final CoreGateway coreGateway;
    private final LlmFallbackChain llmFallbackChain;
    private final AiCallLogWriter aiCallLogWriter;
    private final ObjectMapper objectMapper;
    private final PopupLearnPromptTemplate promptTemplate;

    public PopupLearnServiceImpl(CoreGateway coreGateway, LlmFallbackChain llmFallbackChain,
                                 AiCallLogWriter aiCallLogWriter, ObjectMapper objectMapper,
                                 PopupLearnPromptTemplate promptTemplate) {
        this.coreGateway = coreGateway;
        this.llmFallbackChain = llmFallbackChain;
        this.aiCallLogWriter = aiCallLogWriter;
        this.objectMapper = objectMapper;
        this.promptTemplate = promptTemplate;
    }

    @Override
    public Map<String, Object> learn(PopupLearnRequest request) {
        assertSwitchesOn();
        if (request.taskId() == null || request.projectId() == null) {
            throw new ServiceException("弹窗学习请求缺少任务号或项目号，无法留痕与成本归属",
                    BizErrorCode.VALIDATION_ERROR);
        }
        List<String> images = request.images() == null ? List.of()
                : request.images().stream().filter(s -> s != null && !s.isBlank()).toList();
        if (images.isEmpty()) {
            throw new ServiceException("弹窗学习缺少监看帧素材，无图不烧模型，本次自学零落草稿",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String model = coreGateway.getSysConfig(KEY_MODEL);
        if (model == null || model.isBlank()) {
            throw new ServiceException("视觉模型未配置（系统设置-" + KEY_MODEL
                    + "），弹窗规则自学不可用；不会改用文本模型假装看图，本次自学零落草稿",
                    BizErrorCode.SERVICE_UNAVAILABLE);
        }

        List<String> dataUrls = images.stream()
                .map(b64 -> "data:image/png;base64," + b64)
                .toList();
        LlmChatHandle handle = llmFallbackChain.chatHandle(
                ChatCompletionRequest.of(model, promptTemplate.messages(images.size(), dataUrls,
                        behaviorDirectivesHead())),
                request.projectId(), null);
        writeCallLog(request, handle);

        Map<String, Object> exchange = parseStrict(request.taskId(), handle.result().content());
        log.info("弹窗学习应答 taskId={} frames={} title={} model={}",
                request.taskId(), images.size(), exchange.get("title"),
                handle.result().model());
        return exchange;
    }

    
    private void assertSwitchesOn() {
        if (!SysConfigBooleans.on(coreGateway.getSysConfig(KEY_VISION_ENABLED))
                || !SysConfigBooleans.on(coreGateway.getSysConfig(KEY_LEARN_ENABLED))) {
            throw new ServiceException(DenialGuidanceFrame.switchClosed("弹窗规则自学", KEY_VISION_ENABLED, KEY_LEARN_ENABLED), BizErrorCode.FORBIDDEN);
        }
    }

    
    private Map<String, Object> parseStrict(Long taskId, String content) {
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(JsonOutputExtractor.extractJson(content));
        } catch (Exception e) {
            throw new ServiceException("弹窗识别模型返回的不是可解析的规则 JSON（任务#"
                    + taskId + "），本次自学零落草稿，可稍后重试", BizErrorCode.AI_FORMAT_ERROR);
        }
        if (parsed == null || !parsed.isObject()) {
            throw new ServiceException("弹窗识别模型未返回规则对象（任务#" + taskId
                    + "），本次自学零落草稿", BizErrorCode.AI_FORMAT_ERROR);
        }
        return objectMapper.convertValue(parsed, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    private void writeCallLog(PopupLearnRequest request, LlmChatHandle handle) {
        aiCallLogWriter.insert(request.projectId(), handle.result().provider(),
                handle.result().model(), FUNCTION_NAME,
                handle.result().promptTokens(), handle.result().completionTokens(),
                handle.result().latencyMs());
    }
}
