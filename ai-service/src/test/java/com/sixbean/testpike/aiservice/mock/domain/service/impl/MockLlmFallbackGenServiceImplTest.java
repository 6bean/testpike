package com.sixbean.testpike.aiservice.mock.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.mock.api.dto.MockLlmFallbackGenerateRequest;
import com.sixbean.testpike.aiservice.mock.api.dto.MockLlmFallbackGenerateResult;
import com.sixbean.testpike.aiservice.mock.infrastructure.remote.MockLlmFallbackStoreBrief;
import com.sixbean.testpike.aiservice.mock.infrastructure.remote.MockLlmFallbackStoreInput;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.LlmExtractMarkers;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L2 orchestration tests for the A-3 sync generation door .
 * Infrastructure seams (LLM chain, core HTTP store door) are mocked — real
 * chain quality belongs to L3. The contract pinned here:
 * route project endpoint → one fallback chat whose system prompt carries the
 * MOCK_LLM_FALLBACK marker and whose user prompt carries method/path/contract
 * schema/few-shot samples → parse {status,headers,body} (anything else fails
 * in plain words and NEVER reaches the store door) → case the generation into
 * the cache through core /store with the real token sum → relay core's honest
 * store verdict back to the caller. A provider failure propagates untouched —
 * the traffic plane degrades it to its original miss semantics, never a 500
 * dressed up as a fallback answer.
 */
class MockLlmFallbackGenServiceImplTest {

    private static final long SPACE_ID = 9L;
    private static final long PROJECT_ID = 3L;

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
    private final MockLlmFallbackGenServiceImpl service = new MockLlmFallbackGenServiceImpl(
            coreGateway, llmChain, llmRouter, new ObjectMapper());

    private static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "routed-model", "dummy-key",
                "http://localhost:4000/v1", LlmEndpointConfig.SOURCE_CONFIG);
    }

    private static MockLlmFallbackGenerateRequest request() {
        return new MockLlmFallbackGenerateRequest(SPACE_ID, PROJECT_ID, "POST",
                "/orders/{id}", "fp-digest-1",
                "{\"type\":\"object\",\"properties\":{\"total\":{\"type\":\"integer\"}}}",
                List.of(Map.of("method", "POST", "path", "/orders/1",
                        "request", Map.of("qty", 2), "response", Map.of("total", 200))));
    }

    private void stubLlm(String content, int promptTokens, int completionTokens) {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(llmChain.chat(any(ChatCompletionRequest.class), eq(PROJECT_ID),
                any(LlmFallbackNotice.class), anyBoolean()))
                .thenReturn(new LlmChatResult(content, "openai", "routed-model",
                        promptTokens, completionTokens, 5L));
    }

    @Test
    void generatesParsesAndCasesIntoCoreCacheWithRealTokenSum() {
        stubLlm("{\"status\":201,\"headers\":{\"Content-Type\":\"application/json\"},"
                + "\"body\":\"{\\\"total\\\":200}\"}", 120, 30);
        when(coreGateway.storeMockLlmFallback(any())).thenReturn(
                new MockLlmFallbackStoreBrief(true, "POST|/orders/{id}|fp-digest-1", null));

        MockLlmFallbackGenerateResult result = service.generate(request());

        assertTrue(result.stored(), "core accepted the artifact → stored");
        assertEquals(201, result.status());
        assertEquals(Map.of("Content-Type", "application/json"), result.headers());
        assertEquals("{\"total\":200}", result.body());
        assertEquals(150, result.tokensSpent(), "prompt+completion sum travels to the store door");

        ArgumentCaptor<ChatCompletionRequest> chatCap =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmChain).chat(chatCap.capture(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                anyBoolean());
        String system = chatCap.getValue().messages().get(0).content();
        String user = chatCap.getValue().messages().get(1).content();
        assertTrue(system.contains(LlmExtractMarkers.MOCK_LLM_FALLBACK),
                "system prompt carries the fallback contract marker");
        assertTrue(user.contains("POST") && user.contains("/orders/{id}"),
                "user prompt carries the unmatched request identity");
        assertTrue(user.contains("total"), "contract schema travels into the prompt");

        ArgumentCaptor<MockLlmFallbackStoreInput> storeCap =
                ArgumentCaptor.forClass(MockLlmFallbackStoreInput.class);
        verify(coreGateway).storeMockLlmFallback(storeCap.capture());
        MockLlmFallbackStoreInput stored = storeCap.getValue();
        assertEquals(SPACE_ID, stored.spaceId());
        assertEquals("fp-digest-1", stored.maskedFingerprint());
        assertEquals(150, stored.tokensSpent());
    }

    @Test
    void userPromptCarriesTheSessionExchangesWhenPresent() {
        // session-summary leg: the "exchanges already made on
        // this lane" block rides the user prompt after the few-shot section —
        // continuity context, never vetoed when absent.
        stubLlm("{\"status\":200,\"body\":\"ok\"}", 50, 10);
        when(coreGateway.storeMockLlmFallback(any())).thenReturn(
                new MockLlmFallbackStoreBrief(true, "k", null));

        service.generate(new MockLlmFallbackGenerateRequest(SPACE_ID, PROJECT_ID, "GET",
                "/orders/{id}", "fp-sess", null, List.of(),
                "POST /orders -> 201\nGET /orders/7 -> 200"));

        ArgumentCaptor<ChatCompletionRequest> chatCap =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmChain).chat(chatCap.capture(), eq(PROJECT_ID),
                any(LlmFallbackNotice.class), anyBoolean());
        String user = chatCap.getValue().messages().get(1).content();
        assertTrue(user.contains("POST /orders -> 201")
                        && user.contains("GET /orders/7 -> 200"),
                "the lane's real exchanges ride the prompt, got: " + user);
    }

    @Test
    void userPromptOmitsTheSessionSectionWhenSummaryBlank() {
        stubLlm("{\"status\":200,\"body\":\"ok\"}", 50, 10);
        when(coreGateway.storeMockLlmFallback(any())).thenReturn(
                new MockLlmFallbackStoreBrief(true, "k", null));

        service.generate(request()); // legacy posture: no session summary

        ArgumentCaptor<ChatCompletionRequest> chatCap =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmChain).chat(chatCap.capture(), eq(PROJECT_ID),
                any(LlmFallbackNotice.class), anyBoolean());
        String user = chatCap.getValue().messages().get(1).content();
        assertFalse(user.contains("会话内已发生的交换"),
                "an absent summary invents no prompt section");
    }

    @Test
    void relayStoresHonestRefusalWithoutDressingItUp() {
        stubLlm("{\"status\":200,\"body\":\"ok\"}", 120, 30);
        when(coreGateway.storeMockLlmFallback(any())).thenReturn(
                new MockLlmFallbackStoreBrief(false, "k", "超预算不入库"));

        MockLlmFallbackGenerateResult result = service.generate(request());

        assertFalse(result.stored(), "core refusal relays verbatim — answer still produced");
        assertEquals("超预算不入库", result.storeReason());
        assertEquals(200, result.status());
    }

    @Test
    void nonObjectOutputFailsInPlainWordsAndNeverStores() {
        stubLlm("\"just a string\"", 120, 5);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.generate(request()));
        assertEquals(BizErrorCode.AI_FORMAT_ERROR, ex.getCode());
        verify(coreGateway, never()).storeMockLlmFallback(any());
    }

    @Test
    void missingBodyFieldFailsAsIncompleteArtifactAndNeverStores() {
        stubLlm("{\"status\":200}", 120, 5);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.generate(request()));
        assertEquals(BizErrorCode.AI_FORMAT_ERROR, ex.getCode());
        verify(coreGateway, never()).storeMockLlmFallback(any());
    }

    @Test
    void illegalStatusFromModelFailsAndNeverStores() {
        stubLlm("{\"status\":99,\"body\":\"x\"}", 120, 5);

        assertThrows(ServiceException.class, () -> service.generate(request()));
        verify(coreGateway, never()).storeMockLlmFallback(any());
    }

    @Test
    void providerFailurePropagatesUntouchedForTheMissDegradation() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(llmChain.chat(any(ChatCompletionRequest.class), anyLong(),
                any(LlmFallbackNotice.class), anyBoolean()))
                .thenThrow(new ServiceException("供应商不可达", BizErrorCode.AI_FORMAT_ERROR));

        assertThrows(ServiceException.class, () -> service.generate(request()));
        verify(coreGateway, never()).storeMockLlmFallback(any());
    }

    @Test
    void blankRequestIdentityIsRefusedBeforeAnyTokenBurn() {
        // with a ready answer in the chain, the ONLY way this test passes is the
        // identity guard refusing before the chat — proves zero token burn
        stubLlm("{\"status\":200,\"body\":\"ok\"}", 10, 10);
        MockLlmFallbackGenerateRequest blank = new MockLlmFallbackGenerateRequest(
                null, PROJECT_ID, " GET ", "", "fp", null, List.of());

        assertThrows(ServiceException.class, () -> service.generate(blank));
        verify(llmChain, never()).chat(any(), anyLong(), any(), anyBoolean());
    }
}
