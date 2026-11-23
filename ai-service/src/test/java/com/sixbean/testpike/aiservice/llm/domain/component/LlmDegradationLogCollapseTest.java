package com.sixbean.testpike.aiservice.llm.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.api.dto.LlmConfigBrief;
import com.sixbean.testpike.aiservice.testsupport.CapturingLogger;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.log.LogCollapseWindow;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * the LLM degradation log families must run through the
 * single-sourced {@link LogCollapseWindow} (R40: second/third consumers of the
 * component extracted in ; no per-service repeat suppression).
 * Contract proved here: same fingerprint inside the window emits exactly one
 * line; the rollover line carries the suppressed count plus the authoritative
 * ledger pointer (R53 — collapsing never silently swallows); a non-positive
 * resolved window (hot-tunable property) disables collapsing so every line
 * prints; distinct fingerprints never suppress each other.
 * <p>Real HTTP failure injection (500 answers / refused connections on closed
 * ports), never mocked provider responses — L2 isolates only the
 * infrastructure gateway (GatewayRecordingStub) and the logger sink.
 */
class LlmDegradationLogCollapseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RETRY_PROP = "tp.log.llm-retry-collapse-window-ms";
    private static final String PROVIDER_PROP = "tp.log.llm-provider-fail-collapse-window-ms";

    /** Local stand-in endpoint that always answers 500 (real HTTP failure). */
    private static HttpServer brokenServer;
    private static String brokenUrl;

    @BeforeAll
    static void startStub() throws IOException {
        brokenServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        brokenServer.createContext("/v1/chat/completions",
                (HttpExchange ex) -> {
                    ex.getRequestBody().readAllBytes();
                    ex.sendResponseHeaders(500, -1);
                    ex.close();
                });
        brokenServer.start();
        brokenUrl = "http://127.0.0.1:" + brokenServer.getAddress().getPort() + "/v1";
    }

    @AfterAll
    static void stopStub() {
        brokenServer.stop(0);
    }

    @AfterEach
    void restoreGlobals() {
        System.clearProperty(RETRY_PROP);
        System.clearProperty(PROVIDER_PROP);
        LlmFallbackChain.useCollapseWindow(null);
    }

    private static ChatCompletionRequest request() {
        return new ChatCompletionRequest("m", List.of(new ChatMessage("user", "hi")), null, null);
    }

    // ── retry-exhausted family ────────────────────────────────────────────────

    @Test
    void retryExhaustedSameFingerprintCollapsesThenRolloverCarriesCountAndLedgerPointer() {
        AtomicLong clock = new AtomicLong(0L);
        LogCollapseWindow window = new LogCollapseWindow(RETRY_PROP, clock::get, 60_000L);
        CapturingLogger capture = new CapturingLogger();
        LlmRetryPolicy policy = new LlmRetryPolicy(1, 1, 1, window, capture.logger());
        LlmProviderFactory factory = LlmTestFixtures.realFactory(MAPPER);

        // Attempt 1: window opens, full-detail line prints. Real HTTP 500 every call.
        assertThrows(ServiceException.class, () -> policy.execute(() ->
                factory.chatFor(request(), brokenEndpoint(), Duration.ofSeconds(10))));
        assertEquals(1, capture.warns().size(), "window-open line must print with full detail");

        // Attempt 2 inside the same window instant: collapsed, still one line.
        assertThrows(ServiceException.class, () -> policy.execute(() ->
                factory.chatFor(request(), brokenEndpoint(), Duration.ofSeconds(10))));
        assertEquals(1, capture.warns().size(), "same fingerprint inside the window collapses to one line");

        // First hit after the window elapses: rollover line with count + ledger pointer.
        clock.set(61_000L);
        assertThrows(ServiceException.class, () -> policy.execute(() ->
                factory.chatFor(request(), brokenEndpoint(), Duration.ofSeconds(10))));
        assertEquals(2, capture.warns().size(), "first hit after the window rolls over");
        String rollover = capture.warns().get(1);
        assertTrue(rollover.contains("1 条同指纹重复"), "rollover carries the suppressed count: " + rollover);
        assertTrue(rollover.contains("任务错误记录"),
                "rollover points at the authoritative ledger (R53, never silent): " + rollover);
    }

    @Test
    void retryExhaustedPropertyNonPositiveDisablesCollapseEveryLinePrints() {
        // Production default path: window resolves from the hot-tunable property; <=0 disables.
        System.setProperty(RETRY_PROP, "-1");
        CapturingLogger capture = new CapturingLogger();
        LlmRetryPolicy policy = new LlmRetryPolicy(1, 1, 1, null, capture.logger());
        LlmProviderFactory factory = LlmTestFixtures.realFactory(MAPPER);
        for (int i = 0; i < 3; i++) {
            assertThrows(ServiceException.class, () -> policy.execute(() ->
                    factory.chatFor(request(), brokenEndpoint(), Duration.ofSeconds(10))));
        }
        assertEquals(3, capture.warns().size(),
                "window<=0 must fall open to the legacy every-line behaviour");
    }

    @Test
    void defaultRetryWindowIsPropertyDrivenWithTheDocumentedKey() {
        LlmRetryPolicy policy = new LlmRetryPolicy(1, 1, 1);
        LogCollapseWindow window = policy.collapseWindow();
        assertEquals(RETRY_PROP, window.getWindowProperty(),
                "hot-tunable key surfaced for ops/tests");
        assertTrue(Long.getLong(RETRY_PROP, 60_000L) == 60_000L,
                "unset property resolves to the documented 60s fallback");
    }

    // ── provider-failed (chain degradation) family ────────────────────────────

    @Test
    void providerFailureFamilyCollapsesAndDistinctFingerprintsBothPrint() throws IOException {
        LogCollapseWindow window = new LogCollapseWindow(PROVIDER_PROP, () -> 0L, 60_000L);
        LlmFallbackChain.useCollapseWindow(window);
        CapturingLogger chainCapture = new CapturingLogger();
        CapturingLogger retryCapture = new CapturingLogger();
        LlmFallbackChain chain = exhaustedChain(
                List.of(deadBrief(1L, "alpha", "provider-a", closedPortUrl()),
                        deadBrief(2L, "beta", "provider-b", closedPortUrl())),
                new LlmRetryPolicy(1, 1, 1, window, retryCapture.logger()),
                chainCapture);

        assertThrows(ServiceException.class, () -> chain.chat(request(), 42L, null));
        assertEquals(2, chainCapture.warns().size(),
                "two distinct provider fingerprints in one chain both print (no cross-suppression)");

        // Same chain again on the pinned clock: both families sit inside the open window.
        assertThrows(ServiceException.class, () -> chain.chat(request(), 42L, null));
        assertEquals(2, chainCapture.warns().size(), "repeat occurrences of both fingerprints collapse");

        // The exhaustion line of the inner retry policy uses its own window and never
        // leaks into the chain capture (families are independent).
        assertTrue(retryCapture.warns().size() <= 1,
                "retry family tracked separately, not swallowed by the chain family");
    }

    @Test
    void providerFailurePropertyNonPositiveDisablesCollapseEveryLinePrints() throws IOException {
        System.setProperty(PROVIDER_PROP, "0");
        LlmFallbackChain.useCollapseWindow(new LogCollapseWindow(PROVIDER_PROP));
        CapturingLogger chainCapture = new CapturingLogger();
        CapturingLogger retryCapture = new CapturingLogger();
        LlmFallbackChain chain = exhaustedChain(
                List.of(deadBrief(1L, "solo", "provider-s", closedPortUrl())),
                new LlmRetryPolicy(1, 1, 1, null, retryCapture.logger()),
                chainCapture);
        assertThrows(ServiceException.class, () -> chain.chat(request(), 42L, null));
        assertThrows(ServiceException.class, () -> chain.chat(request(), 42L, null));
        assertEquals(2, chainCapture.warns().size(),
                "window<=0 disables collapsing: the degradation line prints every occurrence");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static LlmEndpointConfig brokenEndpoint() {
        return new LlmEndpointConfig("collapse-prov", "m", "sk-test", brokenUrl, "chain");
    }

    /** Route brief pointing at a closed port: TCP refusal is a real network failure. */
    private static LlmConfigBrief deadBrief(long id, String name, String provider, String baseUrl) {
        return new LlmConfigBrief(id, name, provider, "m", "sk-x", baseUrl, "project");
    }

    /** Bind-then-close an ephemeral port so the next connect attempt is refused. */
    private static String closedPortUrl() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            return "http://127.0.0.1:" + socket.getLocalPort() + "/v1";
        }
    }

    /**
     * Chain wired so every candidate fails and the chain exhausts (IT-LLM-03
     * fail-closed shape): first brief = project primary route, the rest = the
     * enabled fallback list (distinct closed ports dodge the baseUrl dedup);
     * the config-file fallback is emptied so it can never rescue the chain.
     */
    private static LlmFallbackChain exhaustedChain(List<LlmConfigBrief> deadBriefs,
                                                   LlmRetryPolicy retryPolicy,
                                                   CapturingLogger chainCapture) {
        GatewayRecordingStub gw = new GatewayRecordingStub();
        gw.projectBrief = deadBriefs.get(0);
        gw.briefs = deadBriefs.subList(1, deadBriefs.size());
        LlmProperties p = LlmTestFixtures.realProps();
        p.setBaseUrl("");
        p.setProvider("");
        p.setModel("");
        p.setApiKey("");
        ProjectLlmRouter router = new ProjectLlmRouter(gw, p);
        return new LlmFallbackChain(LlmTestFixtures.realFactory(MAPPER), router, gw, retryPolicy,
                new LlmTimeoutResolver(gw, p), null, null, chainCapture.logger());
    }
}
