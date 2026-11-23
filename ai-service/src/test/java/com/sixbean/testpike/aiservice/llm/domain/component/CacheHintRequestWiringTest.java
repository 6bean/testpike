package com.sixbean.testpike.aiservice.llm.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.api.dto.LlmConfigBrief;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prompt-cache hint request-side wiring : the
 * per-model {@code cache_hint} flag decided at the config level must reach the
 * outbound provider request as {@code prompt_cache_key} for multi-turn calls,
 * attached at the single fallback-loop source so every candidate endpoint in a
 * degradation chain carries it identically. Disabled models and single-turn
 * calls serialize zero new fields (NON_NULL keeps legacy request bodies stable).
 */
class CacheHintRequestWiringTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static LlmCapturingStubServer stub;

    @BeforeAll
    static void startStub() throws IOException {
        stub = new LlmCapturingStubServer();
    }

    @AfterAll
    static void stopStub() {
        stub.close();
    }

    private static ChatCompletionRequest multiTurnRequest() {
        return new ChatCompletionRequest("m",
                List.of(new ChatMessage("user", "a"), new ChatMessage("assistant", "b"),
                        new ChatMessage("user", "c")), null, null);
    }

    private static ChatCompletionRequest singleTurnRequest() {
        return new ChatCompletionRequest("m", List.of(new ChatMessage("user", "hi")), null, null);
    }

    private LlmFallbackChain chainWithProjectBrief(LlmConfigBrief projectBrief,
                                                   List<LlmConfigBrief> chainBriefs) {
        GatewayRecordingStub gw = new GatewayRecordingStub();
        gw.projectBrief = projectBrief;
        gw.briefs = chainBriefs;
        LlmProperties p = LlmTestFixtures.realProps();
        return new LlmFallbackChain(LlmTestFixtures.realFactory(MAPPER), new ProjectLlmRouter(gw, p), gw,
                new LlmRetryPolicy(2, 10, 500));
    }

    private static LlmConfigBrief healthyBrief(long id, String provider) {
        return new LlmConfigBrief(id, provider, provider, "m", "sk-test",
                stub.baseUrl(), "project");
    }

    /** TCP-refusing endpoint = real failure injection for the degradation test. */
    private static LlmConfigBrief deadBrief() {
        return new LlmConfigBrief(1L, "primary", "dead-provider", "m", "sk-x",
                "http://127.0.0.1:1/v1", "project");
    }

    @Test
    void enabledMultiTurnRequestCarriesProjectPromptCacheKey() {
        LlmFallbackChain chain = chainWithProjectBrief(healthyBrief(2L, "openai"), List.of());

        chain.chat(multiTurnRequest(), 42L, null);

        String body = stub.lastRequestBody();
        assertNotNull(body, "stub must have served the request");
        assertTrue(body.contains("\"prompt_cache_key\":\"tp-project-42\""),
                "multi-turn + hint enabled must attach the stable project key: " + body);
    }

    @Test
    void singleTurnRequestAttachesNoCacheKey() {
        LlmFallbackChain chain = chainWithProjectBrief(healthyBrief(2L, "openai"), List.of());

        chain.chat(singleTurnRequest(), 42L, null);

        String body = stub.lastRequestBody();
        assertNotNull(body);
        assertFalse(body.contains("prompt_cache_key"),
                "single-turn calls stay key-free (multi-turn criterion messages>2): " + body);
    }

    @Test
    void disabledModelAttachesNoCacheKey() {
        LlmConfigBrief off = new LlmConfigBrief(3L, "off", "openai", "m", "sk-test",
                stub.baseUrl(), "project", null, null, null, null, Boolean.FALSE);
        LlmFallbackChain chain = chainWithProjectBrief(off, List.of());

        chain.chat(multiTurnRequest(), 42L, null);

        String body = stub.lastRequestBody();
        assertNotNull(body);
        assertFalse(body.contains("prompt_cache_key"),
                "cache_hint = 0 (explicitly disabled) must never attach the key: " + body);
    }

    @Test
    void degradedSecondEndpointAttachesSameCacheKey() {
        LlmFallbackChain chain = chainWithProjectBrief(deadBrief(),
                List.of(healthyBrief(9L, "standby-prov")));

        chain.chat(multiTurnRequest(), 42L, null);

        String body = stub.lastRequestBody();
        assertNotNull(body);
        assertTrue(body.contains("\"prompt_cache_key\":\"tp-project-42\""),
                "fallback-loop single source → the degraded endpoint's body carries "
                        + "the identical key: " + body);
    }
}
