package com.sixbean.testpike.aiservice.matricesample.domain.service.impl;

import com.sixbean.testpike.common.pipeline.DenialGuidanceFrame;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelAssembly;
import com.sixbean.testpike.aiservice.llm.domain.component.AiCallLogWriter;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.matricesample.api.dto.MatrixSampleRequest;
import com.sixbean.testpike.aiservice.matricesample.domain.component.MatrixSamplePromptTemplate;
import com.sixbean.testpike.aiservice.matricesample.domain.service.MatrixSampleService;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.JsonOutputExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigBooleans;


@Service
public class MatrixSampleServiceImpl implements MatrixSampleService {

    private static final Logger log = LoggerFactory.getLogger(MatrixSampleServiceImpl.class);

 
    static final String KEY_MATRIX_SAMPLE = "ai.appauto.matrix-sample";

    /** Behaviour-directive gate for this leg (factory default on; explicit 0 closes it). */
    public static final String KEY_DIRECTIVES_ENABLED = "ai.appauto.matrix-sample-directives.enabled";

    /** Template family of this leg (tp_prompt_template name). */
    public static final String TEMPLATE_NAME = "matrix-sample-directives";

    /** Template business domain of this family (tp_prompt_template domain). */
    public static final String TEMPLATE_DOMAIN = "appauto-matrix-sample";

    /**
     * System head for one sampling call through the shared L0 assembly:
     * governance gate → published template row → conservative built-in
     * contract on any miss, kernel invariant appended exactly once.
     */
    String behaviorDirectivesHead() {
        return BehaviorKernelAssembly.assembleHeadFromGateway(coreGateway,
                KEY_DIRECTIVES_ENABLED, TEMPLATE_NAME, TEMPLATE_DOMAIN,
                MatrixSamplePromptTemplate.SYSTEM,
                "行为指令模板读路径不可用，本轮抽样推荐回退内置契约");
    }

    
    private static final String FUNCTION_NAME = "矩阵智能抽样推荐";

    private final CoreGateway coreGateway;
    private final LlmFallbackChain llmFallbackChain;
    private final ProjectLlmRouter projectLlmRouter;
    private final AiCallLogWriter aiCallLogWriter;
    private final ObjectMapper objectMapper;
    private final MatrixSamplePromptTemplate promptTemplate;

    public MatrixSampleServiceImpl(CoreGateway coreGateway, LlmFallbackChain llmFallbackChain,
                                   ProjectLlmRouter projectLlmRouter, AiCallLogWriter aiCallLogWriter,
                                   ObjectMapper objectMapper, MatrixSamplePromptTemplate promptTemplate) {
        this.coreGateway = coreGateway;
        this.llmFallbackChain = llmFallbackChain;
        this.projectLlmRouter = projectLlmRouter;
        this.aiCallLogWriter = aiCallLogWriter;
        this.objectMapper = objectMapper;
        this.promptTemplate = promptTemplate;
    }

    @Override
    public Map<String, Object> sample(MatrixSampleRequest request) {
        assertSwitchOn();
        if (request == null || request.projectId() == null) {
            throw new ServiceException("矩阵抽样请求缺少项目号，无法路由模型与成本归属",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (request.candidates() == null || request.candidates().isEmpty()) {
            throw new ServiceException("矩阵抽样请求缺少候选组合清单（候选面应由 core 程序化实算下传，"
                    + "无候选不烧模型）", BizErrorCode.VALIDATION_ERROR);
        }
        if (request.profile() == null) {
            throw new ServiceException("矩阵抽样请求缺少覆盖率/成本画像（画像应由 core 程序化实算下传，"
                    + "无画像不烧模型）", BizErrorCode.VALIDATION_ERROR);
        }

        String model = projectLlmRouter.route(request.projectId()).modelName();
        LlmChatHandle handle = llmFallbackChain.chatHandle(
                ChatCompletionRequest.of(model, promptTemplate.messages(request, behaviorDirectivesHead())),
                request.projectId(), null);
        writeCallLog(request, handle);

        Map<String, Object> advice = parseAnswer(handle.result().content());
        log.info("矩阵抽样应答 projectId={} caseId={} recommendations={} model={}",
                request.projectId(), request.caseId(),
                ((List<?>) advice.get("recommendations")).size(), handle.result().model());
        return advice;
    }

    
    private void assertSwitchOn() {
        if (!SysConfigBooleans.on(coreGateway.getSysConfig(KEY_MATRIX_SAMPLE))) {
            throw new ServiceException(DenialGuidanceFrame.switchClosed("矩阵智能抽样", KEY_MATRIX_SAMPLE), BizErrorCode.FORBIDDEN);
        }
    }

    
    private Map<String, Object> parseAnswer(String content) {
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(JsonOutputExtractor.extractJson(content));
        } catch (Exception e) {
            throw new ServiceException("模型返回的不是可解析的抽样推荐 JSON，无法采用"
                    + "（请稍后重试）", BizErrorCode.AI_FORMAT_ERROR);
        }
        JsonNode recNode = parsed == null ? null : parsed.path("recommendations");
        if (recNode == null || !recNode.isArray()) {
            throw new ServiceException("模型应答缺少 recommendations 推荐组合数组（契约结构不符），"
                    + "本次推荐不采用", BizErrorCode.AI_FORMAT_ERROR);
        }
        List<Map<String, Object>> recommendations = new ArrayList<>();
        for (int i = 0; i < recNode.size(); i++) {
            JsonNode item = recNode.get(i);
            if (item == null || !item.isObject()) {
                throw new ServiceException("模型产出的第 " + (i + 1)
                        + " 条推荐不是组合对象，本次推荐不采用", BizErrorCode.AI_FORMAT_ERROR);
            }
            Map<String, Object> combo = new LinkedHashMap<>();
            combo.put("engineCode", textOrNull(item, "engineCode"));
            combo.put("platformCode", textOrNull(item, "platformCode"));
            combo.put("deviceKindCode", textOrNull(item, "deviceKindCode"));
            recommendations.add(combo);
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("recommendations", recommendations);
        answer.put("summary", parsed.path("summary").isTextual() ? parsed.path("summary").asText() : "");
        return answer;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isTextual() ? v.asText() : null;
    }

    private void writeCallLog(MatrixSampleRequest request, LlmChatHandle handle) {
        aiCallLogWriter.insert(request.projectId(), handle.result().provider(),
                handle.result().model(), FUNCTION_NAME,
                handle.result().promptTokens(), handle.result().completionTokens(),
                handle.result().latencyMs());
    }
}
