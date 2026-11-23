package com.sixbean.testpike.aiservice.mock.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelAssembly;
import com.sixbean.testpike.aiservice.mock.api.dto.MockLlmFallbackGenerateRequest;
import com.sixbean.testpike.aiservice.mock.api.dto.MockLlmFallbackGenerateResult;
import com.sixbean.testpike.aiservice.mock.domain.component.GeneratedArtifact;
import com.sixbean.testpike.aiservice.mock.domain.service.MockLlmFallbackGenService;
import com.sixbean.testpike.aiservice.mock.infrastructure.remote.MockLlmFallbackStoreBrief;
import com.sixbean.testpike.aiservice.mock.infrastructure.remote.MockLlmFallbackStoreInput;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.JsonOutputExtractor;
import com.sixbean.testpike.common.util.LlmExtractMarkers;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A-3 sync generation leg — the only place in ai-service that
 * spends tokens for a miss-time fallback answer.
 * <p>Order (each step is a RED-pinned contract in the sibling test): guard the
 * request identity BEFORE any chat (a blank space/path/fingerprint can never
 * form a cache key, so burning tokens for it would be pure waste) → route the
 * project endpoint and run one chat under the {@code MOCK_LLM_FALLBACK}
 * marker → parse the {@code {status,headers,body}} artifact through the same
 * structural discipline as the core read side (non-object / missing body /
 * illegal status = plain-word failure, and NOTHING reaches the store door —
 * an incomplete artifact must not become a pre-paid future answer) → case it
 * via core's store door with the real prompt+completion token sum → relay
 * core's honest verdict back to the caller.
 * <p>Failure semantics on purpose: a provider-chain failure propagates
 * untouched. The caller (core orchestrator, then the mock-server miss branch)
 * degrades it to the original miss semantics — never a 500 dressed up as a
 * fallback. Whether gates are open is NOT re-checked here: that spectrum is
 * single-sourced in core (red line 6), and the store door re-gates anyway.
 */
@Service
public class MockLlmFallbackGenServiceImpl implements MockLlmFallbackGenService {

    /** Fallback artifact contract marker (local stub branch judgement; common single source). */
    static final String FALLBACK_MARKER = LlmExtractMarkers.MOCK_LLM_FALLBACK;

    /** Behaviour-directive gate for the miss-time answering leg (factory default on). */
    public static final String KEY_DIRECTIVES_ENABLED = "ai.mock.llm-fallback-directives.enabled";

    /** Template family of this leg (tp_prompt_template name). */
    public static final String TEMPLATE_NAME = "mock-llm-fallback-directives";

    /** Template business domain of this family (tp_prompt_template domain). */
    public static final String TEMPLATE_DOMAIN = "mock-llm-fallback";

    private final CoreGateway coreGateway;
    private final LlmFallbackChain llmFallbackChain;
    private final ProjectLlmRouter projectLlmRouter;
    private final ObjectMapper objectMapper;

    public MockLlmFallbackGenServiceImpl(CoreGateway coreGateway,
                                         LlmFallbackChain llmFallbackChain,
                                         ProjectLlmRouter projectLlmRouter,
                                         ObjectMapper objectMapper) {
        this.coreGateway = coreGateway;
        this.llmFallbackChain = llmFallbackChain;
        this.projectLlmRouter = projectLlmRouter;
        this.objectMapper = objectMapper;
    }

    @Override
    public MockLlmFallbackGenerateResult generate(MockLlmFallbackGenerateRequest request) {
        assertIdentityPresent(request);

        LlmEndpointConfig endpoint = projectLlmRouter.route(request.projectId());
        LlmFallbackNotice notice = new LlmFallbackNotice(null, request.projectId(),
                "AI Mock 兜底代答 " + request.method() + " " + request.path());
        LlmChatResult result = llmFallbackChain.chat(
                new ChatCompletionRequest(endpoint.modelName(), messages(request), null, null),
                request.projectId(), notice, false);

        GeneratedArtifact artifact = parseArtifact(result.content());
        int tokensSpent = Math.max(0, result.promptTokens())
                + Math.max(0, result.completionTokens());

        MockLlmFallbackStoreBrief store = coreGateway.storeMockLlmFallback(
                new MockLlmFallbackStoreInput(request.spaceId(), request.method(),
                        request.path(), request.maskedFingerprint(), artifact.status(),
                        artifact.headers(), artifact.body(), tokensSpent));

        boolean stored = store != null && store.stored();
        return new MockLlmFallbackGenerateResult(artifact.status(), artifact.headers(),
                artifact.body(), tokensSpent, result.model(),
                stored, stored ? null
                        : (store == null ? "core 存储门无应答，本次生成未入库" : store.reason()));
    }

    /** Identity guard: anything the cache key needs must exist before token burn. */
    private void assertIdentityPresent(MockLlmFallbackGenerateRequest request) {
        if (request == null) {
            throw new ServiceException("兜底代答请求为空，拒绝执行（不烧 token）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (request.spaceId() == null) {
            throw new ServiceException("兜底代答请求缺少 spaceId：缓存按空间隔离，"
                    + "无空间无法形成缓存键，拒绝在烧 token 之前", BizErrorCode.VALIDATION_ERROR);
        }
        if (request.path() == null || request.path().isBlank()) {
            throw new ServiceException("兜底代答请求缺少归一化 path：无端点身份无法起草上下文，"
                    + "拒绝在烧 token 之前", BizErrorCode.VALIDATION_ERROR);
        }
        if (request.maskedFingerprint() == null || request.maskedFingerprint().isBlank()) {
            throw new ServiceException("兜底代答请求缺少脱敏指纹：写侧与读侧无配对键，"
                    + "生成即白烧，拒绝在烧 token 之前", BizErrorCode.VALIDATION_ERROR);
        }
    }

    /** System carries the contract; user carries method/path/schema/few-shots. */
    private List<ChatMessage> messages(MockLlmFallbackGenerateRequest request) {
        StringBuilder user = new StringBuilder();
        user.append("未命中的请求：").append(request.method() == null ? "GET"
                : request.method().trim().toUpperCase()).append(' ')
                .append(request.path()).append('\n');
        user.append("脱敏后的请求指纹：").append(request.maskedFingerprint()).append('\n');
        if (request.contractSchemaJson() != null && !request.contractSchemaJson().isBlank()) {
            user.append("端点契约 schema（遵守它，不发明字段）：")
                    .append(request.contractSchemaJson()).append('\n');
        }
        List<Map<String, Object>> samples = request.fewShotSamples();
        if (samples != null && !samples.isEmpty()) {
            user.append("录制样本（同端点族的真实形态，few-shot 参照）：\n");
            for (Map<String, Object> sample : samples) {
                try {
                    user.append("- ").append(objectMapper.writeValueAsString(sample))
                            .append('\n');
                } catch (Exception e) {
                    // a sample that will not serialize is dropped, not fatal —
                    // the answer quality may degrade, the request must not
                }
            }
        }
        // segment 7a session-summary leg: what already happened on this lane,
        // in the caller's own words — continuity so the instant answer fits
        // the flow instead of contradicting earlier exchanges. Absent = no
        // section invented (null-safe like every other context block).
        if (request.sessionSummary() != null && !request.sessionSummary().isBlank()) {
            user.append("会话内已发生的交换（本泳道真实历史，保持前后一致）：\n")
                    .append(request.sessionSummary()).append('\n');
        }
        return List.of(new ChatMessage("system", behaviorDirectivesHead()),
                new ChatMessage("user", user.toString()));
    }

    /**
     * System head for one instant-answer call through the shared L0 assembly:
     * gate → published row → built-in contract fallback on any miss, kernel
     * invariant appended exactly once. The built-in contract keeps the
     * {@code MOCK_LLM_FALLBACK_JSON} marker and the artifact output schema,
     * so the provider stub branch and the parse-side store guard stay intact
     * either way.
     */
    String behaviorDirectivesHead() {
        return BehaviorKernelAssembly.assembleHeadFromGateway(coreGateway,
                KEY_DIRECTIVES_ENABLED, TEMPLATE_NAME, TEMPLATE_DOMAIN,
                buildSystemPrompt(),
                "行为指令模板读路径不可用，本轮兜底代答回退内置契约");
    }

    private String buildSystemPrompt() {
        return "你是 API Mock 兜底应答器（" + FALLBACK_MARKER + "）。"
                + "为未命中 mock 规则的请求即时代答，输出 JSON：\n"
                + "{\"status\":HTTP状态码,\"headers\":{响应头键值对象，可省略},"
                + "\"body\":\"响应正文文本\"}\n"
                + "约束：status 取 100..599；body 为字符串（空正文用 \"\"，不许缺字段）；"
                + "有契约 schema 时必须产出 schema 合规的 body，不发明额外字段；"
                + "无契约 schema 时参照录制样本的真实形态，形态不可知就回最简 JSON 对象。";
    }

    /** Parse the paid output into the store-ready artifact (incomplete = refuse before store). */
    private GeneratedArtifact parseArtifact(String content) {
        String json = JsonOutputExtractor.extractJson(content == null ? "" : content);
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new ServiceException("AI 兜底代答输出不是合法 JSON，本次代答按失败处理"
                    + "（数据面按未命中语义回落）", BizErrorCode.AI_FORMAT_ERROR);
        }
        if (root == null || !root.isObject()) {
            // pairs with the core read side's isObject gate: a scalar/array
            // parses fine yet carries no {status,headers,body} — storing it
            // would case a fake future answer paid for with real tokens
            throw new ServiceException("AI 兜底代答输出缺少 {status,headers,body} 对象结构，"
                    + "本次代答按失败处理（数据面按未命中语义回落）", BizErrorCode.AI_FORMAT_ERROR);
        }
        JsonNode bodyNode = root.path("body");
        if (bodyNode.isMissingNode() || bodyNode.isNull()) {
            throw new ServiceException("AI 兜底代答输出缺 body 字段，产物不完整，"
                    + "拒绝入库（空正文应以 \"\" 表达）", BizErrorCode.AI_FORMAT_ERROR);
        }
        int status = root.path("status").asInt(0);
        if (status < 100 || status > 599) {
            throw new ServiceException("AI 兜底代答输出的 HTTP 状态 " + status
                    + " 不在 100..599，产物不合法，拒绝入库", BizErrorCode.AI_FORMAT_ERROR);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        JsonNode headerNode = root.path("headers");
        if (headerNode.isObject()) {
            headerNode.fields().forEachRemaining(entry -> {
                if (entry.getValue() != null && !entry.getValue().isNull()) {
                    headers.put(entry.getKey(), entry.getValue().asText());
                }
            });
        }
        String body = bodyNode.isTextual() ? bodyNode.asText() : bodyNode.toString();
        return new GeneratedArtifact(status, headers, body);
    }
}
