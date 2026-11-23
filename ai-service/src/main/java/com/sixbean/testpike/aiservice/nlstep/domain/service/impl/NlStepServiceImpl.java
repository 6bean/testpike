package com.sixbean.testpike.aiservice.nlstep.domain.service.impl;

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
import com.sixbean.testpike.aiservice.nlstep.api.dto.NlStepRequest;
import com.sixbean.testpike.aiservice.nlstep.domain.component.NlStepPromptTemplate;
import com.sixbean.testpike.aiservice.nlstep.domain.service.NlStepService;
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
public class NlStepServiceImpl implements NlStepService {

    private static final Logger log = LoggerFactory.getLogger(NlStepServiceImpl.class);

 
    static final String KEY_NL_STEP_ENABLED = "ai.appauto.nl-step";

    
    private static final String FUNCTION_NAME = "自然语言步骤生成";

    /**
 * Governance switch of the behaviour-directive family for this leg (
 * per-domain upgrade): only an explicit 0/false closes the template read;
 * a missing row keeps directives on, same tri-state as the dialog family.
 */
    public static final String KEY_DIRECTIVES_ENABLED = "ai.appauto.nl-step-directives.enabled";

    /** Template family of this leg (tp_prompt_template name). */
    public static final String TEMPLATE_NAME = "nl-step-directives";

    /** Template business domain of this family (tp_prompt_template domain). */
    public static final String TEMPLATE_DOMAIN = "appauto-nl-step";

    /**
     * Behaviour-kernel head for one generation call (R40 single source: gate,
     * read and degrade sequence live in BehaviorKernelAssembly). Fresh read per
     * call; a closed gate performs zero template reads; a failing read degrades
     * to the built-in contract and never takes the generation run down.
     */
    String behaviorDirectivesHead() {
        return BehaviorKernelAssembly.assembleHeadFromGateway(coreGateway,
                KEY_DIRECTIVES_ENABLED, TEMPLATE_NAME, TEMPLATE_DOMAIN,
                NlStepPromptTemplate.SYSTEM,
                "行为指令模板读路径不可用，本轮步骤生成回退内置契约");
    }

    private final CoreGateway coreGateway;
    private final LlmFallbackChain llmFallbackChain;
    private final ProjectLlmRouter projectLlmRouter;
    private final AiCallLogWriter aiCallLogWriter;
    private final ObjectMapper objectMapper;
    private final NlStepPromptTemplate promptTemplate;

    public NlStepServiceImpl(CoreGateway coreGateway, LlmFallbackChain llmFallbackChain,
                             ProjectLlmRouter projectLlmRouter, AiCallLogWriter aiCallLogWriter,
                             ObjectMapper objectMapper, NlStepPromptTemplate promptTemplate) {
        this.coreGateway = coreGateway;
        this.llmFallbackChain = llmFallbackChain;
        this.projectLlmRouter = projectLlmRouter;
        this.aiCallLogWriter = aiCallLogWriter;
        this.objectMapper = objectMapper;
        this.promptTemplate = promptTemplate;
    }

    @Override
    public Map<String, Object> generate(NlStepRequest request) {
        assertSwitchOn();
        if (request.projectId() == null) {
            throw new ServiceException("自然语言步骤生成请求缺少项目号，无法路由模型与成本归属",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (request.description() == null || request.description().isBlank()) {
            throw new ServiceException("自然语言步骤生成缺少操作描述，无素材不烧模型",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (request.allowedVerbs() == null || request.allowedVerbs().isEmpty()) {
            throw new ServiceException("自然语言步骤生成请求缺少允许动词清单"
                    + "（裁剪清单应由 core 编排段按引擎能力位图算出下传），拒绝生成",
                    BizErrorCode.VALIDATION_ERROR);
        }

        String model = projectLlmRouter.route(request.projectId()).modelName();
        LlmChatHandle handle = llmFallbackChain.chatHandle(
                ChatCompletionRequest.of(model, promptTemplate.messages(request, behaviorDirectivesHead())),
                request.projectId(), null);
        writeCallLog(request, handle);

        List<Map<String, Object>> steps = parseSteps(handle.result().content());
        log.info("自然语言步骤生成应答 projectId={} engine={} steps={} model={}",
                request.projectId(), request.engineCode(), steps.size(), handle.result().model());
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("steps", steps);
        return draft;
    }

    
    private void assertSwitchOn() {
        if (!SysConfigBooleans.on(coreGateway.getSysConfig(KEY_NL_STEP_ENABLED))) {
            throw new ServiceException(DenialGuidanceFrame.switchClosed("自然语言步骤生成", KEY_NL_STEP_ENABLED), BizErrorCode.FORBIDDEN);
        }
    }

    
    private List<Map<String, Object>> parseSteps(String content) {
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(JsonOutputExtractor.extractJson(content));
        } catch (Exception e) {
            throw new ServiceException("模型返回的不是可解析的步骤 JSON，无法采用（请把描述"
                    + "写得更具体一些：动作+对象），或稍后重试", BizErrorCode.AI_FORMAT_ERROR);
        }
        JsonNode stepsNode = parsed == null ? null : parsed.path("steps");
        if (stepsNode == null || !stepsNode.isArray()) {
            throw new ServiceException("模型应答缺少 steps 步骤数组（契约结构不符），本次生成不采用",
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

    private void writeCallLog(NlStepRequest request, LlmChatHandle handle) {
        aiCallLogWriter.insert(request.projectId(), handle.result().provider(),
                handle.result().model(), FUNCTION_NAME,
                handle.result().promptTokens(), handle.result().completionTokens(),
                handle.result().latencyMs());
    }
}
