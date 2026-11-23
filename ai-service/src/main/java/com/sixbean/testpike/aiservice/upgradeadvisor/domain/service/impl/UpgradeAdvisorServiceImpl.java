package com.sixbean.testpike.aiservice.upgradeadvisor.domain.service.impl;

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
import com.sixbean.testpike.aiservice.upgradeadvisor.api.dto.UpgradeAdvisorRequest;
import com.sixbean.testpike.aiservice.upgradeadvisor.domain.component.UpgradeAdvisorPromptTemplate;
import com.sixbean.testpike.aiservice.upgradeadvisor.domain.service.UpgradeAdvisorService;
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
public class UpgradeAdvisorServiceImpl implements UpgradeAdvisorService {

    private static final Logger log = LoggerFactory.getLogger(UpgradeAdvisorServiceImpl.class);

 
    static final String KEY_UPGRADE_ADVISOR = "ai.appauto.upgrade-advisor";

    
    private static final String FUNCTION_NAME = "升级顾问风险清单";

    /** Behaviour-directive gate for this leg (factory default on). */
    public static final String KEY_DIRECTIVES_ENABLED = "ai.upgradeadvisor.upgrade-advisor-directives.enabled";

    /** Template family of this leg (tp_prompt_template name). */
    public static final String TEMPLATE_NAME = "upgrade-advisor-directives";

    /** Template business domain of this family (tp_prompt_template domain). */
    public static final String TEMPLATE_DOMAIN = "upgrade-advisor";

    private final CoreGateway coreGateway;
    private final LlmFallbackChain llmFallbackChain;
    private final ProjectLlmRouter projectLlmRouter;
    private final AiCallLogWriter aiCallLogWriter;
    private final ObjectMapper objectMapper;
    private final UpgradeAdvisorPromptTemplate promptTemplate;

    public UpgradeAdvisorServiceImpl(CoreGateway coreGateway, LlmFallbackChain llmFallbackChain,
                                     ProjectLlmRouter projectLlmRouter, AiCallLogWriter aiCallLogWriter,
                                     ObjectMapper objectMapper, UpgradeAdvisorPromptTemplate promptTemplate) {
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
                UpgradeAdvisorPromptTemplate.SYSTEM,
                "行为指令模板读路径不可用，本轮升级顾问回退内置契约");
    }

    @Override
    public Map<String, Object> advise(UpgradeAdvisorRequest request) {
        assertSwitchOn();
        if (request == null || request.projectId() == null) {
            throw new ServiceException("升级顾问请求缺少项目号，无法路由模型与成本归属",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (request.manifestVersion() == null || request.manifestVersion().isBlank()) {
            throw new ServiceException("升级顾问请求缺少目标版本号（要升到哪一版）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (request.profile() == null) {
            throw new ServiceException("升级顾问请求缺少用例特征画像（画像应由 core 程序化实算下传，"
                    + "无画像不烧模型）", BizErrorCode.VALIDATION_ERROR);
        }

        String model = projectLlmRouter.route(request.projectId()).modelName();
        LlmChatHandle handle = llmFallbackChain.chatHandle(
                ChatCompletionRequest.of(model, promptTemplate.messages(behaviorDirectivesHead(), request)),
                request.projectId(), null);
        writeCallLog(request, handle);

        Map<String, Object> advice = parseAdvice(handle.result().content());
        log.info("升级顾问应答 projectId={} version={} risks={} model={}",
                request.projectId(), request.manifestVersion(),
                ((List<?>) advice.get("risks")).size(), handle.result().model());
        return advice;
    }

    
    private void assertSwitchOn() {
        if (!SysConfigBooleans.on(coreGateway.getSysConfig(KEY_UPGRADE_ADVISOR))) {
            throw new ServiceException(DenialGuidanceFrame.switchClosed("升级顾问", KEY_UPGRADE_ADVISOR), BizErrorCode.FORBIDDEN);
        }
    }

    
    private Map<String, Object> parseAdvice(String content) {
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(JsonOutputExtractor.extractJson(content));
        } catch (Exception e) {
            throw new ServiceException("模型返回的不是可解析的升级建议 JSON，无法采用"
                    + "（请稍后重试）", BizErrorCode.AI_FORMAT_ERROR);
        }
        JsonNode risksNode = parsed == null ? null : parsed.path("risks");
        if (risksNode == null || !risksNode.isArray()) {
            throw new ServiceException("模型应答缺少 risks 风险清单数组（契约结构不符），本次征询不采用",
                    BizErrorCode.AI_FORMAT_ERROR);
        }
        List<String> risks = new ArrayList<>();
        for (int i = 0; i < risksNode.size(); i++) {
            JsonNode item = risksNode.get(i);
            if (item == null || !item.isTextual() || item.asText().isBlank()) {
                throw new ServiceException("模型产出的第 " + (i + 1)
                        + " 条风险不是可读文本，本次征询不采用", BizErrorCode.AI_FORMAT_ERROR);
            }
            risks.add(item.asText());
        }
        Map<String, Object> advice = new LinkedHashMap<>();
        advice.put("risks", risks);
        advice.put("summary", parsed.path("summary").isTextual() ? parsed.path("summary").asText() : "");
        return advice;
    }

    private void writeCallLog(UpgradeAdvisorRequest request, LlmChatHandle handle) {
        aiCallLogWriter.insert(request.projectId(), handle.result().provider(),
                handle.result().model(), FUNCTION_NAME,
                handle.result().promptTokens(), handle.result().completionTokens(),
                handle.result().latencyMs());
    }
}
