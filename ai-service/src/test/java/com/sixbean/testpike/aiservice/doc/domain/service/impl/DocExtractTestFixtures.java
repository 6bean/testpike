package com.sixbean.testpike.aiservice.doc.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.doc.domain.component.DocGroupSummaryCache;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmProperties;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmTimeoutResolver;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.common.mq.AiDocExtractMessage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Shared seams and payload factories for the doc-extraction L2 suite: one
 * definition of the mock trio (CoreGateway, LlmFallbackChain,
 * ProjectLlmRouter), the service wiring shapes, the routed endpoint stub and
 * the standard message/chunk builders, consumed by every DocExtract*Test.
 */
final class DocExtractTestFixtures {

    /** One requirement row that passes the schema guard, reused across planes. */
    static final String VALID_JSON =
            "{\"requirements\":[{\"title\":\"锁定异常登录\",\"acceptanceCriteria\":\"连续五次错误锁定\",\n"
                    + "\"chapter\":\"登录 > 异常\",\"confidence\":0.8}]}";

    private final CoreGateway coreGateway = mock(CoreGateway.class);

    /** Chapter citation marker shared by every DocExtract*Test that scans
     *  plane prompts for chapter anchors (single source, R40). */
    static final Pattern CHAPTER_MARK = Pattern.compile("第(\\d+)章锚句");
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
    private final long docId;
    private final long projectId;
    private final String token;

    DocExtractTestFixtures(long docId, long projectId, String token) {
        this.docId = docId;
        this.projectId = projectId;
        this.token = token;
    }

    CoreGateway coreGateway() {
        return coreGateway;
    }

    LlmFallbackChain llmChain() {
        return llmChain;
    }

    /**
 * Single-source plane transport stub :
 * both the blocking and the streaming transport of the fallback chain
 * answer through ONE reply function, so sibling extraction tests never
 * re-describe the same wiring (the duplication gate flagged the copies).
 */
    void stubPlaneTransports(
            java.util.function.Function<
                    com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest,
                    LlmChatResult> reply) {
        when(llmChain.chat(any(), eq(projectId), any(LlmFallbackNotice.class), anyBoolean(), any()))
                .thenAnswer(inv -> reply.apply(inv.getArgument(0)));
        when(llmChain.chatStreamHandle(any(), eq(projectId), any(LlmFallbackNotice.class),
                any(), any()))
                .thenAnswer(inv -> new LlmChatHandle(reply.apply(inv.getArgument(0)), endpoint()));
    }

    ProjectLlmRouter llmRouter() {
        return llmRouter;
    }

    /** Minimal wiring: gateway, chain, router and mapper only. */
    DocExtractServiceImpl pipeline() {
        return new DocExtractServiceImpl(coreGateway, llmChain, llmRouter, new ObjectMapper());
    }

    /** Wiring with the group-summary cache seam (token/planner bridges null). */
    DocExtractServiceImpl pipeline(DocGroupSummaryCache cache) {
        return new DocExtractServiceImpl(coreGateway, llmChain, llmRouter, new ObjectMapper(),
                null, null, null, cache);
    }

    /** Wiring with the plane timeout resolver. */
    DocExtractServiceImpl pipeline(LlmTimeoutResolver timeoutResolver) {
        return new DocExtractServiceImpl(coreGateway, llmChain, llmRouter, new ObjectMapper(),
                null, null, null, null, timeoutResolver);
    }

    LlmTimeoutResolver timeoutResolver() {
        return new LlmTimeoutResolver(coreGateway, new LlmProperties());
    }

    /** Routed endpoint for the project under test (dummy LiteLLM shape). */
    static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "routed-model", "dummy-key",
                "http://localhost:4000/v1", LlmEndpointConfig.SOURCE_CONFIG);
    }

    /** Routed endpoint row with an explicit prompt limit, for budgeting tests. */
    static LlmEndpointConfig endpoint(int promptLimit) {
        return new LlmEndpointConfig("openai", "routed-model", "dummy-key",
                "http://localhost:4000/v1", LlmEndpointConfig.SOURCE_CONFIG, promptLimit);
    }

    /** Consume-path message for the document under test. */
    AiDocExtractMessage message() {
        return new AiDocExtractMessage(docId, projectId, 12L, null, token);
    }

    static Map<String, Object> chunk(int seq, String chapter, String content) {
        return Map.of("seq", seq, "chapter", chapter, "content", content);
    }

    /** Chunk row carrying the exposed database id (the evidence-anchor face:
     *  only blocks with a numeric "id" are attributable server-side). */
    static Map<String, Object> chunkWithId(long id, int seq, String chapter, String content) {
        return Map.of("id", id, "seq", seq, "chapter", chapter, "content", content);
    }

    /** One in-budget login chunk behind the routed endpoint. */
    void stubRoutedChunks() {
        when(llmRouter.route(projectId)).thenReturn(endpoint());
        when(coreGateway.getDocumentChunks(docId)).thenReturn(List.of(
                Map.of("seq", 1, "chapter", "登录", "content", "账号密码正确可登录。")));
    }

    /**
     * Full happy extraction plane: in-budget chunk plus both provider faces
     * stubbed (streaming is the default entry, blocking chat the explicit
     * fallback), so a test pins whichever channel actually runs.
     */
    void stubHappyChain() {
        stubRoutedChunks();
        LlmChatResult extractResult = new LlmChatResult(
                "{\"requirements\":[{\"title\":\"正确账号密码登录\"}]}",
                "openai", "routed-model", 10, 5, 1L);
        when(llmChain.chatStreamHandle(any(), eq(projectId), any(LlmFallbackNotice.class),
                any(), any())).thenReturn(new LlmChatHandle(extractResult, endpoint()));
        when(llmChain.chat(any(), eq(projectId), any(LlmFallbackNotice.class),
                anyBoolean(), any())).thenReturn(extractResult);
    }

    /** Queue provider replies for the retry/regeneration loops (one per call). */
    void stubLlmResponses(String... contents) {
        LlmChatResult[] results = new LlmChatResult[contents.length];
        for (int i = 0; i < contents.length; i++) {
            results[i] = new LlmChatResult(contents[i], "openai", "routed-model", 100, 50, 20L);
        }
        when(llmChain.chat(any(), eq(projectId), any(LlmFallbackNotice.class), anyBoolean(), any()))
                .thenReturn(results[0], java.util.Arrays.copyOfRange(results, 1, results.length));
    }

    /** Clamp handed to the streaming chain by the latest plane call. */
    @SuppressWarnings("unchecked")
    UnaryOperator<Duration> capturedClamp() {
        ArgumentCaptor<UnaryOperator<Duration>> clamp = ArgumentCaptor.forClass(UnaryOperator.class);
        verify(llmChain).chatStreamHandle(any(), eq(projectId), any(LlmFallbackNotice.class),
                any(), clamp.capture());
        return clamp.getValue();
    }
}
