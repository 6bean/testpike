package com.sixbean.testpike.aiservice.llm.infrastructure.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.LlmToolCall;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmStreamEvent;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Streaming consumption of the OpenAI-compatible endpoint (AO batch dialog kernel):
 * the client must turn {@code stream:true} SSE chunks into ordered delta events for
 * the frame protocol, while still returning the same aggregated {@link LlmChatResult}
 * shape non-streaming callers already consume (content, usage, tool calls).
 *
 * <p>The provider stub is a local JDK HttpServer emitting real SSE over a live
 * socket (chunked encoding, flushed per line) — same stand-in class already used
 * by the non-streaming integration test (external vendor unreachable in this
 * environment; honestly labelled, business chain otherwise real HTTP).
 */
class OpenAiCompatClientStreamTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<HttpServer> SERVERS = new ArrayList<>();

    @AfterAll
    static void stopServers() {
        SERVERS.forEach(s -> s.stop(0));
    }

    /** Starts a stub that replays the given SSE payload lines, flushing each one. */
    private static HttpServer startStreamStub(List<String> payloadLines, int status,
                                              AtomicReference<String> capturedRequestBody,
                                              AtomicReference<String> capturedAuth) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                capturedRequestBody.set(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8));
                capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                if (status != 200) {
                    byte[] err = "{\"error\":{\"message\":\"upstream broke\",\"type\":\"server_error\"}}"
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, err.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(err);
                    }
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0); // chunked
                try (OutputStream os = exchange.getResponseBody()) {
                    for (String line : payloadLines) {
                        os.write((line + "\n\n").getBytes(StandardCharsets.UTF_8));
                        os.flush();
                    }
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
        SERVERS.add(server);
        return server;
    }

    private static OpenAiCompatClient clientFor(HttpServer server) {
        return new OpenAiCompatClient("http://localhost:" + server.getAddress().getPort() + "/v1",
                "sk-test-stream-key", Duration.ofSeconds(10), MAPPER);
    }

    private static ChatCompletionRequest request() {
        return ChatCompletionRequest.of("gpt-4o-mini",
                List.of(new ChatMessage("user", "summarize the failing batch")));
    }

    @Test
    void contentDeltasArriveInOrderAndAggregateIntoFinalResult() throws IOException {
        List<String> lines = List.of(
                ": hb", // SSE comment keep-alive line must be ignored, never surfaced
                "data: {\"model\":\"gpt-4o-mini\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"}}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\" the \"}}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"world\"},\"finish_reason\":\"stop\"}]}",
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":3}}",
                "data: [DONE]");
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer server = startStreamStub(lines, 200, body, auth);
        List<LlmStreamEvent> events = new ArrayList<>();

        LlmChatResult result = clientFor(server).chatStream(request(), "openai", events::add);

        // Wire contract: the request must actually ask for streaming + usage in the tail chunk.
        assertTrue(body.get().contains("\"stream\":true"), "stream flag missing: " + body.get());
        assertTrue(body.get().contains("\"include_usage\":true"), "usage option missing: " + body.get());
        assertEquals("Bearer sk-test-stream-key", auth.get());

        assertEquals(List.of("Hello", " the ", "world"),
                events.stream().filter(e -> LlmStreamEvent.KIND_CONTENT.equals(e.kind()))
                        .map(LlmStreamEvent::text).toList());
        assertEquals("Hello the world", result.content());
        assertEquals("openai", result.provider());
        assertEquals("gpt-4o-mini", result.model());
        assertEquals(11, result.promptTokens());
        assertEquals(3, result.completionTokens());
        assertNull(result.toolCalls());
    }

    @Test
    void tailUsageChunkCarriesCachedPromptTokens() throws IOException {
        List<String> lines = List.of(
                "data: {\"model\":\"gpt-4o-mini\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"}}]}",
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":900,\"completion_tokens\":40,"
                        + "\"prompt_tokens_details\":{\"cached_tokens\":768}}}",
                "data: [DONE]");
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer server = startStreamStub(lines, 200, body, auth);
        List<LlmStreamEvent> events = new ArrayList<>();

        LlmChatResult result = clientFor(server).chatStream(request(), "openai", events::add);

        // Prefix-cache reuse counts must survive the streaming tail block too:
        // the calibration page shows hint-on vs hint-off runs side by side.
        assertEquals(768, result.cachedTokens());
        assertEquals(900, result.promptTokens());
        assertEquals(40, result.completionTokens());
    }

    @Test
    void missingPromptTokenDetailsHonestZeroOnStreamPath() throws IOException {
        List<String> lines = List.of(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"}}]}",
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2}}",
                "data: [DONE]");
        HttpServer server = startStreamStub(lines, 200, new AtomicReference<>(), new AtomicReference<>());

        LlmChatResult result = clientFor(server).chatStream(request(), "openai", e -> { });

        // Providers that do not report cache details read 0 — never a made-up number.
        assertEquals(0, result.cachedTokens());
    }

    @Test
    void reasoningDeltasPassThroughAsSeparateReasonEvents() throws IOException {
        List<String> lines = List.of(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"thinking…\"}}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"answer\"}}]}",
                "data: [DONE]");
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = startStreamStub(lines, 200, body, new AtomicReference<>());
        List<LlmStreamEvent> events = new ArrayList<>();

        LlmChatResult result = clientFor(server).chatStream(request(), "openai", events::add);

        assertEquals(List.of(LlmStreamEvent.KIND_REASON, LlmStreamEvent.KIND_CONTENT),
                events.stream().map(LlmStreamEvent::kind).toList());
        assertEquals("thinking…", events.get(0).text());
        // The final body carries only the answer; reasoning is streamed, never merged in.
        assertEquals("answer", result.content());
    }

    @Test
    void toolCallDeltasMergeByIndexIntoOneCompleteCall() throws IOException {
        List<String> lines = List.of(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"run_query\",\"arguments\":\"{\\\"q\\\"\"}}]}}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\":\\\"abc\\\"}\"}}]}}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
                "data: [DONE]");
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = startStreamStub(lines, 200, body, new AtomicReference<>());

        LlmChatResult result = clientFor(server).chatStream(request(), "openai", e -> {
        });

        List<LlmToolCall> calls = result.toolCalls();
        assertEquals(1, calls.size());
        assertEquals("call_1", calls.get(0).id());
        assertEquals("run_query", calls.get(0).name());
        assertEquals("{\"q\":\"abc\"}", calls.get(0).argumentsJson());
    }

    @Test
    void non200UpstreamMapsToProviderErrorWithHonestMessage() throws IOException {
        HttpServer server = startStreamStub(List.of(), 503,
                new AtomicReference<>(), new AtomicReference<>());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> clientFor(server).chatStream(request(), "openai", e -> {
                }));
        assertEquals(BizErrorCode.AI_PROVIDER_ERROR, ex.getCode().intValue());
    }

    @Test
    void streamClosingBeforeDoneStillKeepsWhatWasGenerated() throws IOException {
        // Upstream dies mid-answer: already-delivered deltas must reach the handler;
        // the failure is surfaced honestly afterwards (no silent truncation).
        List<String> lines = List.of(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"}}]}");
        HttpServer server = startStreamStub(lines, 200, new AtomicReference<>(), new AtomicReference<>());
        List<LlmStreamEvent> events = new ArrayList<>();

        // The stub closes cleanly after one chunk; the client sees [DONE]-less EOF.
        // Policy: EOF without [DONE] is an interrupted stream -> error, deltas already delivered.
        ServiceException ex = assertThrows(ServiceException.class,
                () -> clientFor(server).chatStream(request(), "openai", events::add));
        assertEquals(BizErrorCode.AI_PROVIDER_ERROR, ex.getCode().intValue());
        assertEquals(1, events.size());
        assertEquals("partial", events.get(0).text());
    }
}
