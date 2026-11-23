package com.sixbean.testpike.aiservice.llm.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.api.dto.LlmConfigBrief;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.service.LlmProvider;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Streaming transport through the provider factory and the fallback chain
 * . The dialog kernel must be able to forward
 * provider deltas while the fallback chain keeps its adjudicated semantics:
 * retry per endpoint, degrade to the next provider, fail closed on an empty
 * route, and never fake frames in mock mode.
 * <p>The vendor stand-in is a local JDK HttpServer emitting real SSE over a
 * live socket (same stand-in class as OpenAiCompatClientStreamTest — external
 * vendor unreachable in this environment, honestly labelled; the business
 * chain itself runs real HTTP).
 */
class LlmStreamingFallbackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static HttpServer server;
    private static String healthyUrl;

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", LlmStreamingFallbackTest::handle);
        server.start();
        healthyUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterAll
    static void stopStub() {
        server.stop(0);
    }

    /** replays three content deltas + a usage tail chunk + [DONE] */
    private static void handle(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream os = exchange.getResponseBody()) {
            for (String piece : new String[]{"你", "好", "！"}) {
                os.write(("data: {\"choices\":[{\"delta\":{\"content\":\"" + piece + "\"}}]}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            os.write(("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":3,"
                    + "\"completion_tokens\":3}}\n\n").getBytes(StandardCharsets.UTF_8));
            os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
        } finally {
            exchange.close();
        }
    }

    private static ChatCompletionRequest request() {
        return new ChatCompletionRequest("m", List.of(new ChatMessage("user", "hi")), null, null);
    }

    private static LlmEndpointConfig healthyEndpoint(String provider) {
        return new LlmEndpointConfig(provider, "m", "sk-test", healthyUrl, "chain");
    }

    @Test
    void factoryStreamsOrderedDeltasAndReturnsAggregatedResult() {
        List<LlmStreamEvent> events = new ArrayList<>();
        LlmChatResult result = LlmTestFixtures.realFactory(MAPPER).chatStreamFor(request(), healthyEndpoint("only"),
                Duration.ofSeconds(10), events::add);

        assertEquals(List.of("你", "好", "！"),
                events.stream().map(LlmStreamEvent::text).toList(),
                "deltas arrive in provider order, content kind only");
        assertTrue(events.stream().allMatch(e -> LlmStreamEvent.KIND_CONTENT.equals(e.kind())),
                "content pieces are mapped to the content kind");
        assertEquals("你好！", result.content(), "aggregated return shape matches non-streaming callers");
        assertEquals(3, result.completionTokens(), "usage tail chunk feeds billing");
    }

    @Test
    void chainFailClosedOnEmptyRouteStreamsNothing() {
        GatewayRecordingStub gw = new GatewayRecordingStub();
        gw.briefs = List.of();
        LlmProperties p = LlmTestFixtures.realProps();
        p.setBaseUrl("");
        ProjectLlmRouter router = new ProjectLlmRouter(gw, p);
        LlmFallbackChain chain = new LlmFallbackChain(LlmTestFixtures.realFactory(MAPPER), router, gw,
                new LlmRetryPolicy(3, 10, 500));

        List<LlmStreamEvent> events = new ArrayList<>();
        ServiceException ex = assertThrows(ServiceException.class, () ->
                chain.chatStreamHandle(request(), 1L, null, events::add));
        assertEquals(BizErrorCode.AI_PROVIDER_ERROR, ex.getCode(), "50201 fail-closed");
        assertTrue(ex.getMessage().contains("未配置可用 LLM 供应商"), "human-readable: " + ex.getMessage());
        assertTrue(events.isEmpty(), "an empty route must not emit any frame content");
    }

    @Test
    void chainDegradesToSecondProviderKeepingStreamDeltas() {
        GatewayRecordingStub gw = new GatewayRecordingStub();
        gw.briefs = List.of(new LlmConfigBrief(9L, "standby", "standby-prov", "m",
                "sk-test", healthyUrl, "default"));
        gw.projectBrief = new LlmConfigBrief(1L, "primary", "dead-provider", "m", "sk-x",
                "http://127.0.0.1:1/v1", "project");
        LlmFallbackChain chain = new LlmFallbackChain(LlmTestFixtures.realFactory(MAPPER), new ProjectLlmRouter(gw, LlmTestFixtures.realProps()),
                gw, new LlmRetryPolicy(2, 10, 500));

        List<LlmStreamEvent> events = new ArrayList<>();
        LlmChatHandle handle = chain.chatStreamHandle(request(), 42L,
                new LlmFallbackNotice(7L, 42L, "dialog turn"), events::add);

        assertEquals("你好！", handle.result().content(), "degraded provider still streams to the caller");
        assertEquals("standby-prov", handle.activeEndpoint().provider(), "effective endpoint is the survivor");
        assertEquals(3, events.size(), "every streamed delta of the successful endpoint reached the callback");
        assertEquals(1, gw.audits.size(), "fallback audit keeps its E7 semantics on the streaming path");
    }

    @Test
    void mockProviderStreamingPassesThroughWithoutFakeFrames() {
        LlmProperties p = new LlmProperties();
        p.setMockEnabled(true);
        LlmProvider mockProvider = new LlmProvider() {
            @Override
            public String providerName() {
                return "mock";
            }

            @Override
            public LlmChatResult chat(ChatCompletionRequest req) {
                return new LlmChatResult("mock 全量输出", "mock", "mock-model", 1, 2, 1L, null);
            }
        };
        LlmProviderFactory factory = new LlmProviderFactory(mockProvider, p, MAPPER);
        List<LlmStreamEvent> events = new ArrayList<>();

        LlmChatResult result = factory.chatStreamFor(request(), healthyEndpoint("x"),
                Duration.ofSeconds(5), events::add);

        assertEquals("mock 全量输出", result.content(), "dev stand-in passes the whole answer through");
        assertTrue(events.isEmpty(), "mock mode must not fabricate incremental frames");
    }
}
