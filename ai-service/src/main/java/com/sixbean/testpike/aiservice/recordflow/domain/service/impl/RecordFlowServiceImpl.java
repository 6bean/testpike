package com.sixbean.testpike.aiservice.recordflow.domain.service.impl;

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
import com.sixbean.testpike.aiservice.recordflow.api.dto.RecordFlowRequest;
import com.sixbean.testpike.aiservice.recordflow.domain.component.RecordFlowPromptTemplate;
import com.sixbean.testpike.aiservice.recordflow.domain.service.RecordFlowService;
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
public class RecordFlowServiceImpl implements RecordFlowService {

    private static final Logger log = LoggerFactory.getLogger(RecordFlowServiceImpl.class);

 
    static final String KEY_RECORD_TO_FLOW_ENABLED = "ai.appauto.record-to-flow";

    
    private static final String FUNCTION_NAME = "录制转流程反推";

    /**
 * Governance switch of the behaviour-directive family for this leg (
 * per-domain upgrade): only an explicit 0/false closes the template read;
 * a missing row keeps directives on, same tri-state as the dialog family.
 */
    public static final String KEY_DIRECTIVES_ENABLED = "ai.appauto.record-to-flow-directives.enabled";

    /** Template family of this leg (tp_prompt_template name). */
    public static final String TEMPLATE_NAME = "record-flow-directives";

    /** Template business domain of this family (tp_prompt_template domain). */
    public static final String TEMPLATE_DOMAIN = "record-to-flow";

    /**
     * Behaviour-kernel head for one reverse-engineering call (R40 single
     * source: gate, read and degrade sequence live in BehaviorKernelAssembly).
     * Fresh read per call; a closed gate performs zero template reads; a
     * failing read degrades to the built-in contract and never takes the
     * recording-reverse run down.
     */
    String behaviorDirectivesHead() {
        return BehaviorKernelAssembly.assembleHeadFromGateway(coreGateway,
                KEY_DIRECTIVES_ENABLED, TEMPLATE_NAME, TEMPLATE_DOMAIN,
                RecordFlowPromptTemplate.SYSTEM,
                "行为指令模板读路径不可用，本轮录制反推回退内置契约");
    }

    private final CoreGateway coreGateway;
    private final LlmFallbackChain llmFallbackChain;
    private final ProjectLlmRouter projectLlmRouter;
    private final AiCallLogWriter aiCallLogWriter;
    private final ObjectMapper objectMapper;
    private final RecordFlowPromptTemplate promptTemplate;

    public RecordFlowServiceImpl(CoreGateway coreGateway, LlmFallbackChain llmFallbackChain,
                                 ProjectLlmRouter projectLlmRouter, AiCallLogWriter aiCallLogWriter,
                                 ObjectMapper objectMapper, RecordFlowPromptTemplate promptTemplate) {
        this.coreGateway = coreGateway;
        this.llmFallbackChain = llmFallbackChain;
        this.projectLlmRouter = projectLlmRouter;
        this.aiCallLogWriter = aiCallLogWriter;
        this.objectMapper = objectMapper;
        this.promptTemplate = promptTemplate;
    }

    @Override
    public Map<String, Object> generate(RecordFlowRequest request) {
        assertSwitchOn();
        if (request.projectId() == null) {
            throw new ServiceException("录制反推请求缺少项目号，无法路由模型与成本归属",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (request.events() == null || request.events().isEmpty()) {
            throw new ServiceException("录制反推缺少事件序列（无录制素材不烧模型）"
                    + "——请先完成一次录制会话再发起反推",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (request.allowedVerbs() == null || request.allowedVerbs().isEmpty()) {
            throw new ServiceException("录制反推请求缺少允许动词清单"
                    + "（裁剪清单应由 core 编排段按引擎能力位图算出下传），拒绝反推",
                    BizErrorCode.VALIDATION_ERROR);
        }

        String model = projectLlmRouter.route(request.projectId()).modelName();
        LlmChatHandle handle = llmFallbackChain.chatHandle(
                ChatCompletionRequest.of(model, promptTemplate.messages(request, behaviorDirectivesHead())),
                request.projectId(), null);
        writeCallLog(request, handle);

        List<Map<String, Object>> steps = parseSteps(handle.result().content());
        log.info("录制转流程反推应答 projectId={} engine={} events={} steps={} model={}",
                request.projectId(), request.engineCode(), request.events().size(),
                steps.size(), handle.result().model());
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("steps", steps);
        return draft;
    }

    
    private void assertSwitchOn() {
        if (!SysConfigBooleans.on(coreGateway.getSysConfig(KEY_RECORD_TO_FLOW_ENABLED))) {
            throw new ServiceException(DenialGuidanceFrame.switchClosed("录制转流程", KEY_RECORD_TO_FLOW_ENABLED), BizErrorCode.FORBIDDEN);
        }
    }

    
    private List<Map<String, Object>> parseSteps(String content) {
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(JsonOutputExtractor.extractJson(content));
        } catch (Exception e) {
            throw new ServiceException("模型返回的不是可解析的步骤 JSON，无法采用（请确认录制事件"
                    + "序列完整，或稍后重试）", BizErrorCode.AI_FORMAT_ERROR);
        }
        JsonNode stepsNode = parsed == null ? null : parsed.path("steps");
        if (stepsNode == null || !stepsNode.isArray()) {
            throw new ServiceException("模型应答缺少 steps 步骤数组（契约结构不符），本次反推不采用",
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

    private void writeCallLog(RecordFlowRequest request, LlmChatHandle handle) {
        aiCallLogWriter.insert(request.projectId(), handle.result().provider(),
                handle.result().model(), FUNCTION_NAME,
                handle.result().promptTokens(), handle.result().completionTokens(),
                handle.result().latencyMs());
    }
}
