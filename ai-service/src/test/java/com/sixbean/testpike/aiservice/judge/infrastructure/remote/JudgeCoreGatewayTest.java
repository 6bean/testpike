package com.sixbean.testpike.aiservice.judge.infrastructure.remote;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class JudgeCoreGatewayTest {

    private static HttpServer server;
    private static final List<String> GET_PATHS = new CopyOnWriteArrayList<>();
    private static final List<String> POST_BODIES = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/internal/judge/runs/", exchange -> {
            GET_PATHS.add(URLDecoder.decode(exchange.getRequestURI().toString(),
                    StandardCharsets.UTF_8));
            if (exchange.getRequestURI().toString().contains("999")) {
                writeJson(exchange, "{\"code\":\"0\",\"msg\":\"ok\",\"data\":null}");
                return;
            }
            writeJson(exchange, "{\"code\":\"0\",\"msg\":\"ok\",\"data\":{\"id\":5,"
                    + "\"evalsetName\":\"t278j3s.regression\",\"evalsetVersion\":2,"
                    + "\"evalsetDigest\":\"abc123\",\"llmConfigId\":77,\"status\":1,"
                    + "\"rubricSnapshot\":{\"dimensions\":[{\"name\":\"相关性\","
                    + "\"weight\":1.0}],\"wRule\":0.4,\"wLlm\":0.6}}}");
        });
        server.createContext("/internal/judge/evalset/samples", exchange -> {
            GET_PATHS.add(URLDecoder.decode(exchange.getRequestURI().toString(),
                    StandardCharsets.UTF_8));
            if (exchange.getRequestURI().toString().contains("missing")) {
                writeJson(exchange, "{\"code\":\"40001\",\"msg\":\"评测集版本不存在\","
                        + "\"data\":null}");
                return;
            }
            writeJson(exchange, "{\"code\":\"0\",\"msg\":\"ok\",\"data\":[{"
                    + "\"id\":11,\"input\":\"需求A\",\"expected\":\"冻结产出A\","
                    + "\"referenceDocs\":[\"参考件1\"],\"sensitive\":0},"
                    + "{\"id\":12,\"input\":\"需求B\",\"expected\":null,"
                    + "\"referenceDocs\":[],\"sensitive\":1}]}");
        });
        server.createContext("/internal/judge/rule-check", exchange -> {
            POST_BODIES.add(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            writeJson(exchange, "{\"code\":\"0\",\"msg\":\"ok\",\"data\":{\"ruleScore\":60,"
                    + "\"hits\":[\"json_valid:格式检查通过\"],\"shortCircuited\":false}}");
        });
        server.createContext("/internal/judge/records", exchange -> {
            POST_BODIES.add(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            writeJson(exchange, "{\"code\":\"0\",\"msg\":\"ok\",\"data\":{\"id\":8801,"
                    + "\"runId\":5,\"sampleId\":11,\"finalScore\":85}}");
        });
        server.start();
    }

    @AfterAll
    static void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void writeJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private JudgeCoreGateway gateway() {
        return new JudgeCoreGateway("http://localhost:" + server.getAddress().getPort());
    }

    @Test
    void getRun_mapsDualAnchorFields_nullDataMeansStaleDiscard() {
        JudgeRunBrief run = gateway().getRun(5L);
        assertEquals(5L, run.id());
        assertEquals("t278j3s.regression", run.evalsetName());
        assertEquals(2, run.evalsetVersion());
        assertEquals("abc123", run.evalsetDigest());
        assertEquals(77L, run.llmConfigId());
        assertEquals(1, run.status());
        assertTrue(run.rubricSnapshot().containsKey("dimensions"),
                "双锚之一 rubricSnapshot 须原样透传（禁消费侧二次判定丢维度）");
        assertTrue(GET_PATHS.contains("/internal/judge/runs/5"));

        assertNull(gateway().getRun(999L),
                "Run 不存在=data null（core 注释同口径）→ 消费方判空丢弃非错误，禁抛");
    }

    @Test
    void listSamples_queryLineFormat_preservesDotNamespaceAndNullExpected() {
        List<JudgeSampleBrief> samples = gateway()
                .listSamples("t278j3s.regression", 2);
        assertEquals(2, samples.size());
        assertEquals(11L, samples.get(0).id());
        assertEquals("冻结产出A", samples.get(0).expected());
        assertEquals(List.of("参考件1"), samples.get(0).referenceDocs());
        assertEquals(1, samples.get(1).sensitive());
        assertNull(samples.get(1).expected(), "expected null=纯 input 样本（取源缺口行），禁 NPE");
        assertTrue(GET_PATHS.contains(
                        "/internal/judge/evalset/samples?setName=t278j3s.regression&version=2"),
                "集名点分命名空间须原样出现在 query（编码线格式钉死，防 core @RequestParam 解不出）");
    }

    @Test
    void writeRecord_serializesRecordInputContractFields() {
        gateway().writeRecord(new JudgeRecordWrite(5L, 11L, null, List.of(),
                Map.of("相关性", 85.0), Map.of("相关性", "命中需求要点，引用锚见原文"),
                1234L, 900));
        String body = POST_BODIES.get(POST_BODIES.size() - 1);
        assertTrue(body.contains("\"runId\":5"), body);
        assertTrue(body.contains("\"sampleId\":11"), body);
        assertTrue(body.contains("\"相关性\":85.0") || body.contains("\"相关性\":85"), body);
        assertTrue(body.contains("引用锚见原文"), body);
        assertTrue(body.contains("\"latencyMs\":1234"), body);
        assertTrue(body.contains("\"tokens\":900"), body);
    }

    @Test
    void ruleCheck_postsRuleLayerContractAndMirrorsThreeFields() {
        JudgeRuleVerdictBrief verdict = gateway().ruleCheck(
                List.of(Map.of("checker", "json_valid", "params", Map.of())),
                "被评产出", "基准参照");
        assertEquals(60, verdict.ruleScore());
        assertEquals(List.of("json_valid:格式检查通过"), verdict.hits());
        assertTrue(!verdict.shortCircuited(), "shortCircuited=false 原样镜像禁补默认");
        String body = POST_BODIES.get(POST_BODIES.size() - 1);
        assertTrue(body.contains("\"checker\":\"json_valid\""), body);
        assertTrue(body.contains("\"output\":\"被评产出\""), body);
        assertTrue(body.contains("\"expected\":\"基准参照\""), body);
    }

    @Test
    void coreBusinessError_bubblesUpForContainerRetryThenDeadLetter() {
        org.junit.jupiter.api.Assertions.assertThrows(
                com.sixbean.testpike.common.exception.ServiceException.class,
                () -> gateway().listSamples("missing-set", 99),
                "缺版本人话拒必须上抛（重试→死信，IT-MQ-01），禁网关层吞成空列表假绿"
                        + "——对照 listEnabledLlmConfigs 私 catch 口径，落分链静默丢样本=评测假绿");
    }
}
