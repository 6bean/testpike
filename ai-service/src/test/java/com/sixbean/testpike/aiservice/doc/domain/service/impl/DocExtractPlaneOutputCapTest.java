package com.sixbean.testpike.aiservice.doc.domain.service.impl;

import com.sixbean.testpike.aiservice.doc.domain.component.ContextBudgetPlanner;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * remediation (D6 generation-side face): every extraction-plane model
 * call must carry the run's resolved output ceiling (hot key
 * ai.doc.bucket-output-max-tokens) as the request's max_tokens, so a runaway
 * completion can no longer bill past the D6 audit line — the live incident
 * booked completions up to 46,504 tokens against a 24,000 ceiling because the
 * request left the seam with no cap at all.
 * <p>Pinned here: the pure clamp keeps an already-compliant request untouched
 * (never re-wraps, never raises), fills a null max_tokens with the cap,
 * lowers an over-cap value to the cap, and a null/non-positive cap answers
 * the legacy face byte-identical (assembly without the planner publishes no
 * cap). The wiring face pins that the request actually handed to the shared
 * transport carries the hot-read factory default.
 */
class DocExtractPlaneOutputCapTest {

    private static final long DOC_ID = 12L;
    private static final long PROJECT_ID = 7L;
    private static final String TOKEN = "plane-output-cap-token-001";

    private final DocExtractTestFixtures fixtures =
            new DocExtractTestFixtures(DOC_ID, PROJECT_ID, TOKEN);

    /** A request without max_tokens gains the cap. */
    @Test
    void uncappedRequest_gainsOutputCeiling() {
        ChatCompletionRequest capped = DocExtractServiceImpl.capPlaneOutput(
                ChatCompletionRequest.of("m", java.util.List.of()), 24000);
        assertEquals(24000, capped.maxTokens());
    }

    /** An over-cap request is lowered to exactly the cap. */
    @Test
    void overCapRequest_isLoweredToCap() {
        ChatCompletionRequest capped = DocExtractServiceImpl.capPlaneOutput(
                new ChatCompletionRequest("m", java.util.List.of(), null, 46504), 24000);
        assertEquals(24000, capped.maxTokens());
    }

    /** An already-compliant request rides through untouched (identity kept). */
    @Test
    void underCapRequest_staysUntouched() {
        ChatCompletionRequest request =
                new ChatCompletionRequest("m", java.util.List.of(), null, 8000);
        assertSame(request, DocExtractServiceImpl.capPlaneOutput(request, 24000));
    }

    /** Null/non-positive cap = legacy face, byte-identical pass-through. */
    @Test
    void absentCap_passesRequestThrough() {
        ChatCompletionRequest request = ChatCompletionRequest.of("m", java.util.List.of());
        assertSame(request, DocExtractServiceImpl.capPlaneOutput(request, null));
        assertSame(request, DocExtractServiceImpl.capPlaneOutput(request, 0));
    }

    /** Wiring face: the plane call handed to the shared transport carries the
     *  hot-read ceiling (unset key = factory default), not a null cap. */
    @Test
    void planeCallCarriesHotReadCap_toTransport() {
        fixtures.stubRoutedChunks();
        fixtures.stubPlaneTransports(inv -> new LlmChatResult(
                DocExtractTestFixtures.VALID_JSON, "openai", "routed-model", 10, 5, 2L));

        fixtures.pipeline(fixtures.timeoutResolver())
                .extract(fixtures.message());

        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(fixtures.llmChain()).chatStreamHandle(captor.capture(), eq(PROJECT_ID),
                any(LlmFallbackNotice.class), any(), any());
        assertEquals(ContextBudgetPlanner.DEFAULT_BUCKET_OUTPUT_MAX_TOKENS,
                captor.getValue().maxTokens());
    }

    /** The cap is the hot key's live face: an explicit 8000 rides the request. */
    @Test
    void hotKeyCap_flowsIntoPlaneRequest() {
        fixtures.stubRoutedChunks();
        when(fixtures.coreGateway().getSysConfig(
                ContextBudgetPlanner.KEY_BUCKET_OUTPUT_MAX_TOKENS)).thenReturn("8000");
        fixtures.stubPlaneTransports(inv -> new LlmChatResult(
                DocExtractTestFixtures.VALID_JSON, "openai", "routed-model", 10, 5, 2L));

        fixtures.pipeline(fixtures.timeoutResolver()).extract(fixtures.message());

        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(fixtures.llmChain()).chatStreamHandle(captor.capture(), eq(PROJECT_ID),
                any(LlmFallbackNotice.class), any(), any());
        assertEquals(8000, captor.getValue().maxTokens());
    }
}
