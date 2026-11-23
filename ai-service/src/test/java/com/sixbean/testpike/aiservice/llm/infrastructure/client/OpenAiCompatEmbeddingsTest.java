package com.sixbean.testpike.aiservice.llm.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.llm.api.dto.EmbeddingCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmEmbeddingResult;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class OpenAiCompatEmbeddingsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static HttpServer server;
    private static int port;
    
    private static final AtomicReference<String> LAST_BODY = new AtomicReference<>("");
    private static final AtomicInteger RESPONSE_STATUS = new AtomicInteger(200);
    private static final AtomicReference<String> RESPONSE_BODY = new AtomicReference<>("");

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            LAST_BODY.set(body);
            byte[] out = RESPONSE_BODY.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(RESPONSE_STATUS.get(), out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    private static OpenAiCompatClient client() {
        return new OpenAiCompatClient("http://127.0.0.1:" + port + "/v1", "sk-test",
                Duration.ofSeconds(5), MAPPER);
    }

    @Test
    void embed_请求体含model与input数组_响应乱序按index还原() throws Exception {
        RESPONSE_STATUS.set(200);
        RESPONSE_BODY.set("""
                {"data":[
                  {"index":1,"embedding":[0.2,0.3,0.4]},
                  {"index":0,"embedding":[0.1,0.5,0.6]}
                ],"model":"text-embedding-v3","usage":{"total_tokens":8}}""");

        LlmEmbeddingResult result = client().embed(
                new EmbeddingCompletionRequest("text-embedding-v3", List.of("第一条文本", "第二条文本")),
                "bailian");

        JsonNode sent = MAPPER.readTree(LAST_BODY.get());
        assertEquals("text-embedding-v3", sent.path("model").asText(), "请求体 model 字段（OpenAI 兼容契约）");
        assertTrue(sent.path("input").isArray() && sent.path("input").size() == 2,
                "请求体 input 必须为字符串数组：" + LAST_BODY.get());
        assertEquals("第一条文本", sent.path("input").get(0).asText());

        assertEquals(2, result.embeddings().size());
        assertArrayEquals(new float[]{0.1f, 0.5f, 0.6f}, result.embeddings().get(0),
                "index=0 向量还原到第 1 位（乱序响应按 index 排序）");
        assertArrayEquals(new float[]{0.2f, 0.3f, 0.4f}, result.embeddings().get(1),
                "index=1 向量还原到第 2 位");
        assertEquals(8, result.totalTokens());
        assertEquals("text-embedding-v3", result.model());
        assertEquals("bailian", result.provider());
    }

    @Test
    void embed_供应商400_人话错误含供应商信息不裸抛() {
        RESPONSE_STATUS.set(400);
        RESPONSE_BODY.set("{\"error\":{\"message\":\"Model not exist\",\"type\":\"invalid_request_error\"}}");

        ServiceException e = assertThrows(ServiceException.class, () -> client().embed(
                new EmbeddingCompletionRequest("nope-model", List.of("x")), "bailian"));
        assertTrue(e.getMessage().contains("Model not exist"),
                "供应商错误信息必须透传人话：" + e.getMessage());
        assertEquals(BizErrorCode.AI_PROVIDER_ERROR, e.getCode());
    }

    @Test
    void embed_供应商401_密钥错误人话() {
        RESPONSE_STATUS.set(401);
        RESPONSE_BODY.set("{\"error\":{\"message\":\"Invalid API-key provided.\"}}");

        ServiceException e = assertThrows(ServiceException.class, () -> client().embed(
                new EmbeddingCompletionRequest("text-embedding-v3", List.of("x")), "bailian"));
        assertTrue(e.getMessage().contains("Invalid API-key"), e.getMessage());
        assertEquals(BizErrorCode.AI_PROVIDER_ERROR, e.getCode());
    }
}
