package com.sixbean.testpike.aiservice.judge.domain.component;

import com.sixbean.testpike.aiservice.judge.infrastructure.remote.JudgeRunBrief;
import com.sixbean.testpike.aiservice.judge.infrastructure.remote.JudgeSampleBrief;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class EvalTargetAdapterTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private void respond(int status, String body) {
        server.createContext("/ai", exchange -> {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
    }

    private JudgeRunBrief externalRun(String targetRef) {
        return new JudgeRunBrief(9L, "t278j6.ext", 1, "d1", 77L, 1,
                Map.of(), "external", targetRef);
    }

    private JudgeSampleBrief sample() {
        return new JudgeSampleBrief(11L, "需求A：登录返回令牌", "冻结基准", List.of(), 0);
    }

    private String ref(String bodyTemplate, String outputJsonPath) {
        return "{\"url\":\"" + baseUrl + "/ai\",\"method\":\"POST\","
                + "\"bodyTemplate\":\"" + bodyTemplate + "\","
                + "\"outputJsonPath\":\"" + outputJsonPath + "\"}";
    }

    @Test
    void happyPath_postsTemplatedBody_withHeaders_andExtractsAnswer() {
        AtomicReference<String> seenBody = new AtomicReference<>();
        AtomicReference<String> seenToken = new AtomicReference<>();
        server.createContext("/ai", (HttpExchange exchange) -> {
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            seenToken.set(exchange.getRequestHeaders().getFirst("X-Token"));
            byte[] payload = "{\"answer\":\"被测系统回答：登录成功\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        String targetRef = "{\"url\":\"" + baseUrl + "/ai\",\"method\":\"POST\","
                + "\"headers\":{\"X-Token\":\"tk-1\"},"
                + "\"bodyTemplate\":\"{\\\"q\\\":\\\"{{input}}\\\"}\","
                + "\"outputJsonPath\":\"$.answer\"}";

        String text = new HttpEvalTargetAdapter()
                .fetchEvaluatedText(externalRun(targetRef), sample());

        assertEquals("被测系统回答：登录成功", text);
        assertTrue(seenBody.get().contains("需求A：登录返回令牌"),
                "bodyTemplate 占位应替换样本 input，实际=" + seenBody.get());
        assertEquals("tk-1", seenToken.get());
    }

    @Test
    void nonTwoXxResponse_humanReject_withUrlAndStatus() {
        respond(500, "boom");
        ServiceException e = assertThrows(ServiceException.class,
                () -> new HttpEvalTargetAdapter().fetchEvaluatedText(
                        externalRun(ref("{{input}}", "$.answer")), sample()));
        assertTrue(e.getMessage().contains("/ai"), e.getMessage());
        assertTrue(e.getMessage().contains("500"), e.getMessage());
    }

    @Test
    void extractMiss_humanReject_withPath() {
        respond(200, "{\"other\":\"x\"}");
        ServiceException e = assertThrows(ServiceException.class,
                () -> new HttpEvalTargetAdapter().fetchEvaluatedText(
                        externalRun(ref("{{input}}", "$.answer")), sample()));
        assertTrue(e.getMessage().contains("$.answer"), e.getMessage());
    }

    @Test
    void extractedBlank_humanReject() {
        respond(200, "{\"answer\":\"  \"}");
        ServiceException e = assertThrows(ServiceException.class,
                () -> new HttpEvalTargetAdapter().fetchEvaluatedText(
                        externalRun(ref("{{input}}", "$.answer")), sample()));
        assertTrue(e.getMessage().contains("空"), e.getMessage());
    }

    @Test
    void connectionRefused_humanRejectNoFakeGreen() throws IOException {
        HttpServer temp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        temp.start();
        int port = temp.getAddress().getPort();
        temp.stop(0);
        String targetRef = "{\"url\":\"http://127.0.0.1:" + port + "/ai\","
                + "\"outputJsonPath\":\"$.answer\"}";
        ServiceException e = assertThrows(ServiceException.class,
                () -> new HttpEvalTargetAdapter().fetchEvaluatedText(
                        externalRun(targetRef), sample()));
        assertTrue(e.getMessage().contains("不可达") || e.getMessage().contains("失败"),
                e.getMessage());
    }

    @Test
    void malformedTargetRef_humanReject() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> new HttpEvalTargetAdapter().fetchEvaluatedText(
                        externalRun("not-a-json"), sample()));
        assertTrue(e.getMessage().contains("target_ref"), e.getMessage());
    }

    @Test
    void inputJsonPath_extractsScalar_beforeTemplating() {
        AtomicReference<String> seenBody = new AtomicReference<>();
        server.createContext("/ai", exchange -> {
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            byte[] payload = "{\"answer\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        JudgeSampleBrief structured = new JudgeSampleBrief(11L,
                "{\"requirement\":\"嵌套需求文\"}", "冻结基准", List.of(), 0);
        String targetRef = "{\"url\":\"" + baseUrl + "/ai\","
                + "\"inputJsonPath\":\"$.requirement\","
                + "\"bodyTemplate\":\"{\\\"q\\\":\\\"{{input}}\\\"}\","
                + "\"outputJsonPath\":\"$.answer\"}";

        assertEquals("ok", new HttpEvalTargetAdapter()
                .fetchEvaluatedText(externalRun(targetRef), structured));
        assertTrue(seenBody.get().contains("嵌套需求文"), seenBody.get());
    }

    @Test
    void registry_unknownAdapter_humanRejectNamingSupported() {
        EvalTargetAdapterRegistry registry = new EvalTargetAdapterRegistry(
                List.of(new HttpEvalTargetAdapter()));
        String targetRef = "{\"adapter\":\"ws\",\"url\":\"" + baseUrl + "/ai\","
                + "\"outputJsonPath\":\"$.answer\"}";
        ServiceException e = assertThrows(ServiceException.class,
                () -> registry.resolve(externalRun(targetRef)));
        assertTrue(e.getMessage().contains("ws"), e.getMessage());
        assertTrue(e.getMessage().contains("http"), e.getMessage());
    }

    @Test
    void registry_defaultAdapter_isHttp() {
        EvalTargetAdapterRegistry registry = new EvalTargetAdapterRegistry(
                List.of(new HttpEvalTargetAdapter()));
        assertEquals("http",
                registry.resolve(externalRun(ref("{{input}}", "$.answer"))).type());
    }
}
