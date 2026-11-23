package com.sixbean.testpike.aiservice.llm.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.api.dto.LlmConfigBrief;
import com.sixbean.testpike.aiservice.llm.domain.service.impl.MockLlmProvider;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * DD batch the shared LLM fallback door is the single place that
 * books provider calls into {@code tp_ai_call_log}, so an extraction generation
 * answers "which calls happened and what did each cost" ( double-burn
 * audit reads the token column) and R53 can classify a failure by its error
 * code instead of losing it in a folded WARN line.
 *
 * <p>L2 honesty: the call path is real HTTP — a local JDK stand-in endpoint for
 * the success shape, a refused TCP port for the failure shape (real network
 * fault injection, not a stubbed answer). {@code CoreGateway} is isolated with
 * {@link GatewayRecordingStub} and the writer with Mockito, both infrastructure
 * boundaries; the chain, router, retry policy and client run untouched.
 *
 * <p>Pinned here: success rows carry endpoint identity, reported usage, measured
 * latency and the generation token with a null error code; failure rows carry
 * zero tokens (cost honestly unknown), the measured latency and the numeric
 * error code; the local stand-in door books through the same seam under the
 * routed identity (otherwise the ledger goes blind in exactly the environment
 * chains are rehearsed in); a writer outage is swallowed; an unwired door keeps
 * every other plane byte-identical (zero rows); and a spent window seam stops
 * the attempt before the next provider call is burned.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LlmFallbackChainLedgerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long PROJECT_ID = 7L;
    private static final String TOKEN = "gen-token-568";
    /** TCP port 1 = connection refused (real transport failure injection). */
    private static final String DEAD_URL = "http://127.0.0.1:1/v1";

    @Mock
    private AiCallLogWriter writer;

    /** One attempt, zero backoff: one row per endpoint, no timing noise. */
    private final LlmRetryPolicy retryPolicy = new LlmRetryPolicy(1, 0, 1);

    // ── assembly helpers ────────────────────────────────────────────────────

    private static LlmConfigBrief brief(long id, String provider, String model, String baseUrl) {
        return new LlmConfigBrief(id, "cfg-" + id, provider, model, "sk-test", baseUrl, "project");
    }

    /** Router + resolver over the recording gateway stub (infra isolation). */
    private static ProjectLlmRouter router(GatewayRecordingStub gw, LlmProperties props) {
        return new ProjectLlmRouter(gw, props);
    }

    private LlmFallbackChain chain(LlmProviderFactory factory, ProjectLlmRouter router,
                                   GatewayRecordingStub gw, LlmProperties props) {
        LlmFallbackChain chain = new LlmFallbackChain(factory, router, gw, retryPolicy,
                new LlmTimeoutResolver(gw, props));
        chain.enableCallLedger(writer, () -> TOKEN, "chat");
        return chain;
    }

    private static ChatCompletionRequest request() {
        // modelHint absent: the routed endpoint model is kept (E7 semantics)
        return new ChatCompletionRequest(null,
                List.of(new ChatMessage("user", "hello")), null, null);
    }

    // ── success shape ───────────────────────────────────────────────────────

    /** Real HTTP 200 from the local stand-in: exactly one row with endpoint
     *  identity, reported usage (1/2 from the stub body), measured latency,
     *  the generation token and a null error code. */
    @Test
    void successfulCall_landsOneRowWithGenerationTokenAndNoErrorCode() throws Exception {
        try (LlmCapturingStubServer stub = new LlmCapturingStubServer()) {
            GatewayRecordingStub gw = new GatewayRecordingStub();
            gw.projectBrief = brief(9L, "openai", "routed-model", stub.baseUrl());
            gw.briefs = List.of();
            LlmProperties props = LlmTestFixtures.realProps();
            LlmFallbackChain chain = chain(LlmTestFixtures.realFactory(MAPPER),
                    router(gw, props), gw, props);

            LlmChatHandle handle = chain.chatHandle(request(), PROJECT_ID, null);

            assertEquals("ok", handle.result().content());
            assertNotNull(handle.result());
            verify(writer).insert(eq(PROJECT_ID), eq("openai"), eq("routed-model"),
                    eq("chat"), eq(1), eq(2), anyLong(), eq(TOKEN), isNull());
            verify(writer, never()).insert(any(), any(), any(), any(),
                    anyInt(), anyInt(), anyLong(), any(), eq(TOKEN));
        }
    }

    // ── failure shape ───────────────────────────────────────────────────────

    /** Connection refused (real transport fault): the burn is still booked —
     *  zero tokens honestly unknown, measured latency, the numeric error code
     *  of the provider failure (50201 = service error per the interface-design
     *  failure-semantics section). */
    @Test
    void failedCall_landsRowWithMeasuredLatencyAndNumericErrorCode() {
        GatewayRecordingStub gw = new GatewayRecordingStub();
        gw.projectBrief = brief(9L, "openai", "routed-model", DEAD_URL);
        gw.briefs = List.of();
        LlmProperties props = LlmTestFixtures.realProps();
        LlmFallbackChain chain = chain(LlmTestFixtures.realFactory(MAPPER),
                router(gw, props), gw, props);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> chain.chatHandle(request(), PROJECT_ID, null));

        assertEquals(BizErrorCode.AI_PROVIDER_ERROR, ex.getCode(),
                "unreachable provider keeps the 50201 classification");
        verify(writer).insert(eq(PROJECT_ID), eq("openai"), eq("routed-model"),
                eq("chat"), eq(0), eq(0), anyLong(), eq(TOKEN),
                eq(String.valueOf(BizErrorCode.AI_PROVIDER_ERROR)));
    }

    // ── local stand-in door ─────────────────────────────────────────────────

    /** The mock stand-in path is booked through the same seam (never silently
     *  dropped) under its own provider/model face with the generation token:
     *  the trail always answers "which calls happened", including in the
     *  stand-in environment. */
    @Test
    void mockStandInDoor_landsRowUnderItsOwnProviderIdentity() {
        LlmProperties props = LlmTestFixtures.realProps();
        props.setMockEnabled(true);
        GatewayRecordingStub gw = new GatewayRecordingStub();
        gw.projectBrief = brief(9L, "openai", "routed-model", DEAD_URL);
        LlmProviderFactory factory = new LlmProviderFactory(
                new MockLlmProvider("mock-model"), props, MAPPER);
        LlmFallbackChain chain = chain(factory, router(gw, props), gw, props);

        LlmChatHandle handle = chain.chatHandle(request(), PROJECT_ID, null);

        assertNotNull(handle.result().content());
        verify(writer).insert(eq(PROJECT_ID), eq("mock"), eq("mock-model"),
                eq("chat"), anyInt(), anyInt(), anyLong(), eq(TOKEN), isNull());
    }

    // ── observability must never break the call ─────────────────────────────

    /** A writer outage is swallowed: the provider answer still comes back
     *  (same posture as AiCallLogWriter itself — one row never fails a run). */
    @Test
    void writerOutage_neverBreaksTheProviderCall() throws Exception {
        try (LlmCapturingStubServer stub = new LlmCapturingStubServer()) {
            GatewayRecordingStub gw = new GatewayRecordingStub();
            gw.projectBrief = brief(9L, "openai", "routed-model", stub.baseUrl());
            gw.briefs = List.of();
            LlmProperties props = LlmTestFixtures.realProps();
            doThrow(new IllegalStateException("db down")).when(writer).insert(any(), any(), any(),
                    any(), anyInt(), anyInt(), anyLong(), any(), any());
            LlmFallbackChain chain = chain(LlmTestFixtures.realFactory(MAPPER),
                    router(gw, props), gw, props);

            LlmChatHandle handle = chain.chatHandle(request(), PROJECT_ID, null);

            assertEquals("ok", handle.result().content(), "ledger failure stays invisible to caller");
            verify(writer).insert(eq(PROJECT_ID), eq("openai"), eq("routed-model"),
                    eq("chat"), eq(1), eq(2), anyLong(), eq(TOKEN), isNull());
        }
    }

    // ── non-extraction planes: row still lands, token column null ───────────

    /** Callers without a generation token (chat plane and every other door):
     *  the row lands with a null token — that is exactly the non-extraction
     *  face, so the extraction query stays the only filtered set. */
    @Test
    void doorWithoutToken_stillBooksRowWithNullToken() throws Exception {
        try (LlmCapturingStubServer stub = new LlmCapturingStubServer()) {
            GatewayRecordingStub gw = new GatewayRecordingStub();
            gw.projectBrief = brief(9L, "openai", "routed-model", stub.baseUrl());
            gw.briefs = List.of();
            LlmProperties props = LlmTestFixtures.realProps();
            LlmFallbackChain chain = new LlmFallbackChain(LlmTestFixtures.realFactory(MAPPER),
                    router(gw, props), gw, retryPolicy, new LlmTimeoutResolver(gw, props));
            chain.enableCallLedger(writer, () -> null, "chat");

            chain.chatHandle(request(), PROJECT_ID, null);

            verify(writer).insert(eq(PROJECT_ID), eq("openai"), eq("routed-model"),
                    eq("chat"), eq(1), eq(2), anyLong(), isNull(), isNull());
        }
    }

    /** Unwired door = zero rows: every plane that never called enableCallLedger
     *  keeps its legacy byte-identical behavior (no shared-side drift). */
    @Test
    void unwiredDoor_booksNothing() throws Exception {
        try (LlmCapturingStubServer stub = new LlmCapturingStubServer()) {
            GatewayRecordingStub gw = new GatewayRecordingStub();
            gw.projectBrief = brief(9L, "openai", "routed-model", stub.baseUrl());
            gw.briefs = List.of();
            LlmProperties props = LlmTestFixtures.realProps();
            LlmFallbackChain chain = new LlmFallbackChain(LlmTestFixtures.realFactory(MAPPER),
                    router(gw, props), gw, retryPolicy, new LlmTimeoutResolver(gw, props));

            LlmChatHandle handle = chain.chatHandle(request(), PROJECT_ID, null);

            assertEquals("ok", handle.result().content());
            verify(writer, never()).insert(any(), any(), any(), any(),
                    anyInt(), anyInt(), anyLong(), any(), any());
        }
    }

    // ── window seam ─────────────────────────────────────────────────────────

    /** A spent task window hard-stops before the provider call is attempted:
 * zero HTTP requests burned, zero rows booked, AI_TIMEOUT surfaced
 * (pairs with the / deadline semantics). */
    @Test
    void spentWindowSeam_stopsBeforeTheNextProviderAttempt() throws Exception {
        try (LlmCapturingStubServer stub = new LlmCapturingStubServer()) {
            GatewayRecordingStub gw = new GatewayRecordingStub();
            gw.projectBrief = brief(9L, "openai", "routed-model", stub.baseUrl());
            gw.briefs = List.of();
            LlmProperties props = LlmTestFixtures.realProps();
            LlmFallbackChain chain = new LlmFallbackChain(LlmTestFixtures.realFactory(MAPPER),
                    router(gw, props), gw, retryPolicy, new LlmTimeoutResolver(gw, props));
            chain.enableCallLedger(writer, () -> TOKEN, "chat",
                    timeout -> {
                        assertNotNull(timeout, "seam is asked with the resolved base timeout");
                        return null; // spent window = the clamp has nothing left to give
                    });

            ServiceException ex = assertThrows(ServiceException.class,
                    () -> chain.chatHandle(request(), PROJECT_ID, null));

            assertEquals(BizErrorCode.AI_TIMEOUT, ex.getCode(), "spent window = 50401 hard stop");
            assertNull(stub.lastRequestBody(), "no provider call was burned");
            verify(writer, never()).insert(any(), any(), any(), any(),
                    anyInt(), anyInt(), anyLong(), any(), any());
        }
    }

    /** Seam identity (Duration passthrough) keeps the call alive: the clamp is
     *  asked per attempt, it is not a stop switch by itself. */
    @Test
    void liveWindowSeam_passesThroughAndBooksTheRow() throws Exception {
        try (LlmCapturingStubServer stub = new LlmCapturingStubServer()) {
            GatewayRecordingStub gw = new GatewayRecordingStub();
            gw.projectBrief = brief(9L, "openai", "routed-model", stub.baseUrl());
            gw.briefs = List.of();
            LlmProperties props = LlmTestFixtures.realProps();
            LlmFallbackChain chain = new LlmFallbackChain(LlmTestFixtures.realFactory(MAPPER),
                    router(gw, props), gw, retryPolicy, new LlmTimeoutResolver(gw, props));
            chain.enableCallLedger(writer, () -> TOKEN, "chat", timeout -> timeout);

            LlmChatHandle handle = chain.chatHandle(request(), PROJECT_ID, null);

            assertEquals("ok", handle.result().content());
            assertNotNull(stub.lastRequestBody(), "the attempt really ran over HTTP");
            verify(writer).insert(eq(PROJECT_ID), eq("openai"), eq("routed-model"),
                    eq("chat"), eq(1), eq(2), anyLong(), eq(TOKEN), isNull());
        }
    }

    /** Duration seam type guard: the plane hands a clamp, the door never
     *  re-derives its own timeout (K8 hot update stays single-sourced). */
    @Test
    void windowSeam_receivesTheResolvedTimeoutPerAttempt() throws Exception {
        try (LlmCapturingStubServer stub = new LlmCapturingStubServer()) {
            GatewayRecordingStub gw = new GatewayRecordingStub();
            gw.projectBrief = brief(9L, "openai", "routed-model", stub.baseUrl());
            gw.briefs = List.of();
            LlmProperties props = LlmTestFixtures.realProps();
            java.util.concurrent.atomic.AtomicInteger asked =
                    new java.util.concurrent.atomic.AtomicInteger();
            LlmFallbackChain chain = new LlmFallbackChain(LlmTestFixtures.realFactory(MAPPER),
                    router(gw, props), gw, retryPolicy, new LlmTimeoutResolver(gw, props));
            chain.enableCallLedger(writer, () -> TOKEN, "chat", timeout -> {
                asked.incrementAndGet();
                return timeout == null ? Duration.ofSeconds(1) : timeout;
            });

            chain.chatHandle(request(), PROJECT_ID, null);

            assertEquals(1, asked.get(), "seam asked exactly once per attempt");
        }
    }
}
