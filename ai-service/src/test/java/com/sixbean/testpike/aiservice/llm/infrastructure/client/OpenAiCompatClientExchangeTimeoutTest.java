package com.sixbean.testpike.aiservice.llm.infrastructure.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.api.dto.EmbeddingCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmProperties;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exchange-level timeout guard tests (root cause 2026-11-05, rerun window 762).
 * <p>Spring 6.2.8 JdkClientHttpRequest arms its TimeoutHandler only AFTER response
 * headers arrive, so the readTimeout alone never bounds the park on
 * {@code responseFuture.get()} before headers (jstack evidence: thread parked
 * 15min+ with readTimeout configured). An upstream that accepts the TCP connection
 * but never answers therefore hangs the whole call until the client now applies
 * its own exchange-level deadline at construction time. These tests pin the stall
 * form honestly (5e: the stub replays the exact observed upstream behaviour —
 * bytes accepted, zero response headers) and assert the client surfaces the
 * standard model-timeout error within that bound instead of parking forever.
 */
class OpenAiCompatClientExchangeTimeoutTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static HttpServer healthyServer;

    /**
     * Hang stub: accept connections, drain request bytes, send nothing back —
     * the exact shape of the LiteLLM stall captured in jstack at 19:48:36.
     */
    private static ServerSocket startHeaderStallStub() throws IOException {
        ServerSocket server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
        Thread accepter = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    Thread closer = new Thread(() -> {
                        try {
                            Thread.sleep(120_000);
                            socket.close();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (IOException ignored) {
                            // socket already gone
                        }
                    }, "header-stall-closer");
                    closer.setDaemon(true);
                    closer.start();
                    // Drain so the client finishes sending the request body, then stall.
                    InputStream in = socket.getInputStream();
                    byte[] buf = new byte[4096];
                    while (in.read(buf) > 0) {
                        // discard
                    }
                } catch (IOException e) {
                    return; // server socket closed at test end
                }
            }
        }, "header-stall-acceptor");
        accepter.setDaemon(true);
        accepter.start();
        return server;
    }

    private static Throwable callBounded(Runnable call, long probeSeconds) throws Exception {
        ExecutorService probe = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "exchange-timeout-probe");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<?> future = probe.submit(call);
            future.get(probeSeconds, TimeUnit.SECONDS);
            return null; // call returned without error — recorded by the test
        } catch (ExecutionException e) {
            return e.getCause();
        } finally {
            probe.shutdownNow();
        }
    }

    @AfterAll
    static void stopHealthy() {
        if (healthyServer != null) {
            healthyServer.stop(0);
        }
    }

    @Test
    void 响应头永不到达时对话调用在交换预算内中断而非挂死() throws Exception {
        try (ServerSocket stall = startHeaderStallStub()) {
            Duration timeout = Duration.ofSeconds(2);
            OpenAiCompatClient client = new OpenAiCompatClient(
                    "http://127.0.0.1:" + stall.getLocalPort() + "/v1", "sk-stall-test",
                    timeout, MAPPER);
            ChatCompletionRequest request = new ChatCompletionRequest("gpt-4o-mini",
                    List.of(new ChatMessage("user", "ping")), null, null);
            long start = System.nanoTime();
            Throwable thrown = callBounded(() -> client.chat(request, "litellm"), 15);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertNotNull(thrown, "stalled upstream must surface an error, not hang the exchange");
            assertTrue(thrown instanceof ServiceException,
                    "expected ServiceException from the exchange guard, got " + thrown);
            assertEquals(BizErrorCode.AI_TIMEOUT, ((ServiceException) thrown).getCode(),
                    "stall must map to the model-timeout code 50401");
            assertTrue(elapsedMs >= timeout.toMillis(),
                    "guard must not fire before the configured budget, took " + elapsedMs + "ms");
            assertTrue(elapsedMs < 12_000,
                    "guard must fire shortly after the budget, took " + elapsedMs + "ms");
        }
    }

    @Test
    void 响应头永不到达时向量调用同样受交换预算保护() throws Exception {
        try (ServerSocket stall = startHeaderStallStub()) {
            Duration timeout = Duration.ofSeconds(2);
            OpenAiCompatClient client = new OpenAiCompatClient(
                    "http://127.0.0.1:" + stall.getLocalPort() + "/v1", "sk-stall-test",
                    timeout, MAPPER);
            Throwable thrown = callBounded(() -> client.embed(
                    new EmbeddingCompletionRequest("text-embedding-v3", List.of("x")),
                    "bailian"), 15);
            assertNotNull(thrown, "embed must not hang on a stalled upstream either");
            assertTrue(thrown instanceof ServiceException);
            assertEquals(BizErrorCode.AI_TIMEOUT, ((ServiceException) thrown).getCode());
        }
    }

    @Test
    void 空交换预算回退装机单源而非无界等待() {
        // A null budget must never reach setReadTimeout — setReadTimeout(null) leaves
        // the pre-header exchange park unbounded (the 762 field failure shape). The
        // constructor falls back to the installed single source LLM_TIMEOUT_SECONDS,
        // then the LlmProperties factory default: the guard is never silently off
        // (same conservative-fallback discipline as ExtractDeadline). Bounded-when-
        // non-null behaviour under a stalling upstream is pinned by the two stall
        // tests above; these cases pin that every client therefore has a budget.
        assertEquals(Duration.ofSeconds(2),
                OpenAiCompatClient.boundedExchangeBudget(null, "2"));
        assertEquals(Duration.ofSeconds(7),
                OpenAiCompatClient.boundedExchangeBudget(Duration.ofSeconds(7), "2"));
        assertEquals(Duration.ofSeconds(LlmProperties.DEFAULT_TIMEOUT_SECONDS),
                OpenAiCompatClient.boundedExchangeBudget(null, null));
        assertEquals(Duration.ofSeconds(LlmProperties.DEFAULT_TIMEOUT_SECONDS),
                OpenAiCompatClient.boundedExchangeBudget(null, "garbage"));
        assertEquals(Duration.ofSeconds(LlmProperties.DEFAULT_TIMEOUT_SECONDS),
                OpenAiCompatClient.boundedExchangeBudget(null, "0"));
        assertDoesNotThrow(() -> new OpenAiCompatClient("http://127.0.0.1:1/v1",
                "«redacted:sk-…»", null, MAPPER));
    }

    @Test
    void 正常快速响应不受交换闸影响() throws Exception {
        if (healthyServer == null) {
            healthyServer = HttpServer.create(new InetSocketAddress(0), 0);
            healthyServer.createContext("/v1/chat/completions", exchange -> {
                String json = """
                        {"id":"cmpl-guard-1","object":"chat.completion","model":"gpt-4o-mini",
                         "choices":[{"index":0,"message":{"role":"assistant","content":"正常返回"},
                         "finish_reason":"stop"}],
                         "usage":{"prompt_tokens":5,"completion_tokens":3}}
                        """;
                byte[] resp = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }
            });
            healthyServer.start();
        }
        OpenAiCompatClient client = new OpenAiCompatClient(
                "http://127.0.0.1:" + healthyServer.getAddress().getPort() + "/v1",
                "sk-guard-test", Duration.ofSeconds(30), MAPPER);
        LlmChatResult result = client.chat(new ChatCompletionRequest("gpt-4o-mini",
                List.of(new ChatMessage("user", "ping")), null, null), "litellm");
        assertEquals("正常返回", result.content());
        assertEquals(5, result.promptTokens());
    }
}
