package com.sixbean.testpike.aiservice.llm.infrastructure.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.service.impl.OpenAiCompatLlmProvider;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmProperties;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class OpenAiCompatClientIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static HttpServer serverA;
    private static HttpServer serverB;
    private static final AtomicInteger serverACalls = new AtomicInteger();
    private static final AtomicInteger serverBCalls = new AtomicInteger();
    private static final AtomicReference<String> lastAuthHeader = new AtomicReference<>("");

    @BeforeAll
    static void startStubs() throws IOException {
        serverA = startServer("供应商A-生成结果", 200, 0);
        serverB = startServer("供应商B-生成结果", 200, 0);
    }

    @AfterAll
    static void stopStubs() {
        if (serverA != null) {
            serverA.stop(0);
        }
        if (serverB != null) {
            serverB.stop(0);
        }
    }

    private static HttpServer startServer(String content, int status, long delayMs) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                if (delayMs > 0) {
                    Thread.sleep(delayMs);
                }
                lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
                if (status != 200) {
                    byte[] err = ("{\"error\":{\"message\":\"upstream 5xx\",\"type\":\"server_error\"}}")
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, err.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(err);
                    }
                    return;
                }
                String body = """
                        {"id":"cmpl-1","object":"chat.completion","model":"gpt-4o-mini","choices":[
                          {"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
                         "usage":{"prompt_tokens":100,"completion_tokens":50}}
                        """.formatted(content);
                byte[] resp = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private static int port(HttpServer server) {
        return server.getAddress().getPort();
    }

    private ChatCompletionRequest request() {
        return new ChatCompletionRequest("gpt-4o-mini",
                List.of(new ChatMessage("system", "你是测试专家"), new ChatMessage("user", "登录功能生成用例")),
                0.3, 2000);
    }

    @Test
    void TC_E1_01_请求真实走供应商A_响应正常() {
        serverACalls.set(0);
        LlmProperties props = new LlmProperties();
        props.setMockEnabled(false);
        props.setBaseUrl("http://127.0.0.1:" + port(serverA) + "/v1");
        props.setApiKey("sk-integration-test-key");
        OpenAiCompatLlmProvider provider = new OpenAiCompatLlmProvider(props, MAPPER);

        LlmChatResult r = provider.chat(request());

        assertEquals("供应商A-生成结果", r.content());
        assertEquals("openai", r.provider());
        assertEquals("gpt-4o-mini", r.model());
        assertEquals(100, r.promptTokens());
        assertEquals(50, r.completionTokens());
        assertTrue(r.latencyMs() >= 0);
        assertEquals("Bearer sk-integration-test-key", lastAuthHeader.get());
    }

    @Test
    void TC_E1_02_切换供应商B_无代码改动() {
        serverBCalls.set(0);
        LlmProperties props = new LlmProperties();
        props.setMockEnabled(false);
        props.setProvider("deepseek");
        props.setBaseUrl("http://127.0.0.1:" + port(serverB) + "/v1");
        props.setApiKey("sk-integration-test-key");
        OpenAiCompatLlmProvider provider = new OpenAiCompatLlmProvider(props, MAPPER);

        LlmChatResult r = provider.chat(request());

        assertEquals("供应商B-生成结果", r.content());
        assertEquals("deepseek", r.provider());
    }

    @Test
    void TC_E1_03_供应商5xx_抛服务异常50201() throws IOException {
        HttpServer bad = startServer("", 503, 0);
        try {
            LlmProperties props = new LlmProperties();
            props.setMockEnabled(false);
            props.setBaseUrl("http://127.0.0.1:" + bad.getAddress().getPort() + "/v1");
            props.setApiKey("sk-integration-test-key");
            OpenAiCompatLlmProvider provider = new OpenAiCompatLlmProvider(props, MAPPER);

            ServiceException ex = assertThrows(ServiceException.class, () -> provider.chat(request()));
            assertEquals(BizErrorCode.AI_PROVIDER_ERROR, ex.getCode().intValue());
        } finally {
            bad.stop(0);
        }
    }

    @Test
    void 调用超时_抛模型超时50401() throws IOException {
        HttpServer slow = startServer("慢响应", 200, 3000);
        try {
            // The provider seam floors every production mint to the 800s
            // model-request budget (R56), so a sub-floor exchange is assembled
            // straight on the client constructor here — this test pins the
            // timeout taxonomy (50401), not the floor seam.
            OpenAiCompatClient client = new OpenAiCompatClient(
                    "http://127.0.0.1:" + slow.getAddress().getPort() + "/v1",
                    "sk-int...-key",
                    java.time.Duration.ofSeconds(1), MAPPER);

            ServiceException ex = assertThrows(ServiceException.class,
                    () -> client.chat(request(), "openai"));
            assertEquals(BizErrorCode.AI_TIMEOUT, ex.getCode().intValue());
        } finally {
            slow.stop(0);
        }
    }
}
