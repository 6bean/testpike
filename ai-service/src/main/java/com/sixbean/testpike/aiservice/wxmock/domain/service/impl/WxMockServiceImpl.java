package com.sixbean.testpike.aiservice.wxmock.domain.service.impl;

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
import com.sixbean.testpike.aiservice.wxmock.api.dto.WxMockRequest;
import com.sixbean.testpike.aiservice.wxmock.domain.component.WxMockPromptTemplate;
import com.sixbean.testpike.aiservice.wxmock.domain.service.WxMockService;
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
public class WxMockServiceImpl implements WxMockService {

    private static final Logger log = LoggerFactory.getLogger(WxMockServiceImpl.class);

 
    static final String KEY_WX_MOCK_ENABLED = "ai.appauto.wx-mock";

    
    private static final String FUNCTION_NAME = "小程序mock数据集生成";

    /** Behaviour-directive gate for this leg (factory default on). */
    public static final String KEY_DIRECTIVES_ENABLED = "ai.wxmock.wxmock-gen-directives.enabled";

    /** Template family of this leg (tp_prompt_template name). */
    public static final String TEMPLATE_NAME = "wxmock-gen-directives";

    /** Template business domain of this family (tp_prompt_template domain). */
    public static final String TEMPLATE_DOMAIN = "wxmock-gen";

    private final CoreGateway coreGateway;
    private final LlmFallbackChain llmFallbackChain;
    private final ProjectLlmRouter projectLlmRouter;
    private final AiCallLogWriter aiCallLogWriter;
    private final ObjectMapper objectMapper;
    private final WxMockPromptTemplate promptTemplate;

    public WxMockServiceImpl(CoreGateway coreGateway, LlmFallbackChain llmFallbackChain,
                             ProjectLlmRouter projectLlmRouter, AiCallLogWriter aiCallLogWriter,
                             ObjectMapper objectMapper, WxMockPromptTemplate promptTemplate) {
        this.coreGateway = coreGateway;
        this.llmFallbackChain = llmFallbackChain;
        this.projectLlmRouter = projectLlmRouter;
        this.aiCallLogWriter = aiCallLogWriter;
        this.objectMapper = objectMapper;
        this.promptTemplate = promptTemplate;
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
                WxMockPromptTemplate.SYSTEM,
                "行为指令模板读路径不可用，本轮小程序mock生成回退内置契约");
    }

    @Override
    public Map<String, Object> generate(WxMockRequest request) {
        assertSwitchOn();
        if (request.projectId() == null) {
            throw new ServiceException("小程序 mock 生成请求缺少项目号，无法路由模型与成本归属",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (request.contract() == null || request.contract().isEmpty()) {
            throw new ServiceException("小程序 mock 生成缺少接口契约样例（无契约不烧模型）"
                    + "——请先提供接口的请求/响应字段定义再生成 mock 数据集",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (request.genFunctions() == null || request.genFunctions().isEmpty()) {
            throw new ServiceException("小程序 mock 生成请求缺少可用生成函数清单"
                    + "（清单应由 core 编排段按 genkit 注册表下传），拒绝生成",
                    BizErrorCode.VALIDATION_ERROR);
        }

        String model = projectLlmRouter.route(request.projectId()).modelName();
        LlmChatHandle handle = llmFallbackChain.chatHandle(
                ChatCompletionRequest.of(model, promptTemplate.messages(behaviorDirectivesHead(), request)),
                request.projectId(), null);
        writeCallLog(request, handle);

        List<Map<String, Object>> mocks = parseMocks(handle.result().content());
        log.info("小程序mock智辅应答 projectId={} api={} methods={} mocks={} model={}",
                request.projectId(), request.apiPath(), request.method(),
                mocks.size(), handle.result().model());
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("mocks", mocks);
        return draft;
    }

    
    private void assertSwitchOn() {
        if (!SysConfigBooleans.on(coreGateway.getSysConfig(KEY_WX_MOCK_ENABLED))) {
            throw new ServiceException(DenialGuidanceFrame.switchClosed("小程序 mock 智辅", KEY_WX_MOCK_ENABLED), BizErrorCode.FORBIDDEN);
        }
    }

    
    private List<Map<String, Object>> parseMocks(String content) {
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(JsonOutputExtractor.extractJson(content));
        } catch (Exception e) {
            throw new ServiceException("模型返回的不是可解析的 mock 数据集 JSON，无法采用"
                    + "（请确认接口契约样例完整，或稍后重试）", BizErrorCode.AI_FORMAT_ERROR);
        }
        JsonNode mocksNode = parsed == null ? null : parsed.path("mocks");
        if (mocksNode == null || !mocksNode.isArray()) {
            throw new ServiceException("模型应答缺少 mocks 数据集数组（契约结构不符），本次生成不采用",
                    BizErrorCode.AI_FORMAT_ERROR);
        }
        List<Map<String, Object>> mocks = objectMapper.convertValue(mocksNode,
                objectMapper.getTypeFactory().constructCollectionType(
                        java.util.ArrayList.class,
                        objectMapper.getTypeFactory().constructMapType(
                                java.util.LinkedHashMap.class, String.class, Object.class)));
        for (int i = 0; i < mocks.size(); i++) {
            if (mocks.get(i) == null) {
                throw new ServiceException("模型应答第 " + (i + 1)
                        + " 个 mock 场景不是 JSON 对象，场景形不完整不采用",
                        BizErrorCode.AI_FORMAT_ERROR);
            }
        }
        return mocks;
    }

    private void writeCallLog(WxMockRequest request, LlmChatHandle handle) {
        aiCallLogWriter.insert(request.projectId(), handle.result().provider(),
                handle.result().model(), FUNCTION_NAME,
                handle.result().promptTokens(), handle.result().completionTokens(),
                handle.result().latencyMs());
    }
}
