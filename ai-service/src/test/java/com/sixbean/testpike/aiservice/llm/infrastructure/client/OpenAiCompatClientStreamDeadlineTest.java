package com.sixbean.testpike.aiservice.llm.infrastructure.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Streaming slow-drip guard tests (root cause 2026-11-05, rerun evidence 763:
 * an 1801s finish kept alive by heartbeat comment lines).
 * <p>Once the JDK HttpClient hands the SSE body back as a raw InputStream, the
 * request-level timeout no longer covers the body segment, so {@code consumeStream}
 * must re-arm two guards over the read loop itself: an absolute exchange deadline
 * (same budget the constructor normalizes — single source, no second literal) and
 * an inter-line idle bound fed by the K8-hot key. The stub replays the exact
 * observed upstream shape (5e): {@code :hb} heartbeat comment lines and data
 * deltas over chunked text/event-stream, so a heartbeat-fed drip is cut instead
 * of parking the calling thread forever.
 */
class OpenAiCompatClientStreamDeadlineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * Starts a chunked text/event-stream stub whose body is written by {@code script}
     * on the exchange thread; handler-side failures only ever kill this exchange and
     * never reach the test thread (the client-side guard is what the test asserts).
     */
    private void dripThenClose(long budgetSeconds, long idleSeconds,
                               java.util.function.Consumer<OutputStream> script) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0); // chunked
            try (OutputStream os = exchange.getResponseBody()) {
                script.accept(os);
            } catch (Exception ignored) {
                // guard tripped and the client walked away — expected shape
            }
        });
        server.start();
        OpenAiCompatClient client = new OpenAiCompatClient(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "sk-stream-guard-placeholder", Duration.ofSeconds(budgetSeconds),
                Duration.ofSeconds(idleSeconds), MAPPER);
        clientRequest = new ChatCompletionRequest("gpt-4o-mini",
                List.of(new ChatMessage("user", "ping")), null, null);
        this.client = client;
    }

    private OpenAiCompatClient client;
    private ChatCompletionRequest clientRequest;

    private static void writeLine(OutputStream os, String line) {
        try {
            os.write((line + "\n\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e); // exchange thread only
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String dataChunk(String content) {
        return "data: {\"choices\":[{\"delta\":{\"content\":\"" + content + "\"}}]}";
    }

    /**
     * Shape 1 — inter-line silence beyond the idle bound: one chunk, then a
     * 3s dead gap while the idle gate sits at 1s. The guard must surface the
     * standard model-timeout error shortly after the gap opens, keeping every
     * delta that already arrived (honest-partial posture of the transport).
     */
    @Test
    void 线间停顿超空闲闸时流式调用按模型超时中断() throws Exception {
        dripThenClose(30, 1, os -> {
            writeLine(os, dataChunk("第一段"));
            writeLine(os, ":hb");
            sleepQuietly(3000);
            writeLine(os, dataChunk("永远读不到的第二段"));
            writeLine(os, "data: [DONE]");
        });
        List<String> delivered = new ArrayList<>();
        long start = System.nanoTime();
        ServiceException thrown = assertThrows(ServiceException.class,
                () -> client.chatStream(clientRequest, "litellm",
                        event -> delivered.add(event.text())));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertEquals(BizErrorCode.AI_TIMEOUT, thrown.getCode(),
                "idle breach must map to the model-timeout code 50401 via the shared taxonomy, got "
                        + thrown.getMessage());
        assertTrue(elapsedMs >= 1000, "idle gate must not fire before the bound, took " + elapsedMs + "ms");
        assertTrue(elapsedMs < 6000, "idle gate must cut the silence, took " + elapsedMs + "ms");
        assertTrue(delivered.contains("第一段"), "deltas already streamed must stay delivered");
    }

    /**
     * Shape 2 — heartbeat-fed slow drip inside every idle window: a line every
     * 150ms forever (server caps the abuse at 5s and cuts the stream). The idle
     * gate never fires, so the absolute exchange deadline (2s budget) must.
     */
    @Test
    void 心跳保活慢滴漏超绝对预算时流式调用被强制截止() throws Exception {
        AtomicInteger dripped = new AtomicInteger();
        dripThenClose(2, 10, os -> {
            long stop = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < stop) {
                writeLine(os, ":hb"); // same keep-alive shape as the 763 field log
                writeLine(os, dataChunk("滴" + dripped.incrementAndGet()));
                sleepQuietly(150);
            }
        });
        List<String> delivered = new ArrayList<>();
        long start = System.nanoTime();
        ServiceException thrown = assertThrows(ServiceException.class,
                () -> client.chatStream(clientRequest, "litellm",
                        event -> delivered.add(event.text())));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertEquals(BizErrorCode.AI_TIMEOUT, thrown.getCode(),
                "deadline breach must map to the model-timeout code 50401, got " + thrown.getMessage());
        assertTrue(elapsedMs >= 2000, "deadline must not fire before the budget, took " + elapsedMs + "ms");
        assertTrue(elapsedMs < 3500, "drip must be cut at the deadline, not at the stub's 5s cap, took "
                + elapsedMs + "ms");
        assertTrue(delivered.size() >= 1, "chunks streamed before the cut stay delivered");
    }

    /** Control — a healthy stream under both bounds completes untouched. */
    @Test
    void 正常节奏流式响应不受双闸影响() throws Exception {
        dripThenClose(30, 10, os -> {
            writeLine(os, ":hb");
            writeLine(os, dataChunk("正常"));
            sleepQuietly(200);
            writeLine(os, dataChunk("收尾"));
            writeLine(os, "data: [DONE]");
        });
        assertNotNull(client);
        var result = client.chatStream(clientRequest, "litellm", event -> {
        });
        assertEquals("正常收尾", result.content());
    }
}
