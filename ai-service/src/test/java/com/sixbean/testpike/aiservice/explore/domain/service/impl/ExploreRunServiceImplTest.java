package com.sixbean.testpike.aiservice.explore.domain.service.impl;

import com.sixbean.testpike.aiservice.explore.domain.component.ExploreLoopKernel;
import com.sixbean.testpike.aiservice.explore.infrastructure.remote.ExploreSessionBrief;
import com.sixbean.testpike.aiservice.explore.infrastructure.remote.ExploreStepRecord;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.common.clientobject.ExploreRunStatsUpdate;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.ExploreRunMessage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;


class ExploreRunServiceImplTest {

    private static HttpServer server;
    private static final List<String> GET_PATHS = new CopyOnWriteArrayList<>();
    private static final List<String> STATS_CALLS = new CopyOnWriteArrayList<>();
    private static final List<String> STATS_BODIES = new CopyOnWriteArrayList<>();
    private static final List<String> STEP_CALLS = new CopyOnWriteArrayList<>();
    private static final List<String> STEP_BODIES = new CopyOnWriteArrayList<>();
    private static final List<String> STATUS_CALLS = new CopyOnWriteArrayList<>();
    private static final List<String> STATUS_BODIES = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/internal/explore/sessions/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/steps")) {
                STEP_CALLS.add(exchange.getRequestMethod() + " " + path);
                STEP_BODIES.add(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8));
                writeJson(exchange, "{\"code\":\"0\",\"msg\":\"ok\",\"data\":null}");
                return;
            }
            if (path.endsWith("/status")) {
                STATUS_CALLS.add(exchange.getRequestMethod() + " " + path);
                STATUS_BODIES.add(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8));
                writeJson(exchange, "{\"code\":\"0\",\"msg\":\"ok\",\"data\":null}");
                return;
            }
            if (path.endsWith("/stats")) {
                STATS_CALLS.add(exchange.getRequestMethod() + " " + path);
                STATS_BODIES.add(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8));
                writeJson(exchange, "{\"code\":\"0\",\"msg\":\"ok\",\"data\":null}");
                return;
            }
            GET_PATHS.add(path);
            long id = Long.parseLong(path.substring("/internal/explore/sessions/".length()));
            String body;
            if (id == 404L) {
                body = "{\"code\":\"40401\",\"msg\":\"探索会话不存在\",\"data\":null}";
            } else if (id == 202L) {
                body = sessionJson(id, 2);
            } else {
                body = sessionJson(id, 1);
            }
            writeJson(exchange, body);
        });
        server.start();
    }

    @AfterAll
    static void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void reset() {
        GET_PATHS.clear();
        STATS_CALLS.clear();
        STATS_BODIES.clear();
    }

    
    private static void writeJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    
    private static String sessionJson(long id, int status) {
        return "{\"code\":\"0\",\"msg\":\"ok\",\"data\":{\"id\":" + id
                + ",\"projectId\":1,\"goalText\":\"摸清下单流程\",\"startUrl\":\"http://localhost:5173\","
                + "\"status\":" + status + ",\"stepsUsed\":0,\"pagesVisited\":0,\"costCents\":0,"
                + "\"reportMd\":null,\"createdBy\":7,"
                + "\"budgetSnapshot\":\"{\\\"maxSteps\\\":30,\\\"maxPages\\\":20,\\\"noProgressLimit\\\":3}\"}}";
    }

    private ExploreRunServiceImpl service() {
        return service(mock(ExploreLoopKernel.class));
    }

    private ExploreRunServiceImpl service(ExploreLoopKernel kernel) {
        return new ExploreRunServiceImpl(
                new CoreGateway("http://localhost:" + server.getAddress().getPort()), kernel);
    }

    
    @Test
    void 运行中会话委托内核执行() {
        ExploreLoopKernel kernel = mock(ExploreLoopKernel.class);
        service(kernel).run(new ExploreRunMessage(101L, 1L, 7L));
        verify(kernel).run(any(ExploreSessionBrief.class), eq(7L));
    }

    
    @Test
    void 终态会话内核零触碰() {
        ExploreLoopKernel kernel = mock(ExploreLoopKernel.class);
        service(kernel).run(new ExploreRunMessage(202L, 1L, 7L));
        verifyNoInteractions(kernel);
    }

    @Test
    void 回读会话_运行中占位_GET路径与载荷会话一致() {
        service().run(new ExploreRunMessage(101L, 1L, 7L));
        assertEquals(List.of("/internal/explore/sessions/101"), GET_PATHS,
                "骨架必须回读 core 会话详情（URL 契约）");
    }

    @Test
    void 回读会话_终态跳过不再进循环() {
        service().run(new ExploreRunMessage(202L, 1L, 7L));
        assertEquals(1, GET_PATHS.size(), "终态会话回读后跳过（重投竞态保护）");
    }

    @Test
    void 回读会话_不存在异常上抛进重试死信() {
        ExploreRunServiceImpl service = service();
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.run(new ExploreRunMessage(404L, 1L, 7L)));
        assertTrue(ex.getMessage().contains("探索会话不存在"),
                "core 40401 人话消息必须透传（禁伪装 500）");
    }

    
    @Test
    void 步进回写_POST路径与载荷字段契约() {
        CoreGateway gateway = new CoreGateway(
                "http://localhost:" + server.getAddress().getPort());
        gateway.recordExploreStep(505L, new ExploreStepRecord(3, "{\"action\":\"click\"}",
                "{\"ok\":true}", "1/explore/202609/abc-step3.png", null, "思考摘要", null));
        assertEquals(List.of("POST /internal/explore/sessions/505/steps"), STEP_CALLS,
                "步进必须 POST 且路径锁定（core internal 契约）");
        String body = STEP_BODIES.get(0);
        assertTrue(body.contains("\"seq\":3"), "seq 线名字段，实际=" + body);
        assertTrue(body.contains("\"stepAction\""), "stepAction 线名字段");
        assertTrue(body.contains("\"stepResult\""), "stepResult 线名字段");
        assertTrue(body.contains("\"screenshotKey\""), "screenshotKey 线名字段");
        assertTrue(body.contains("\"llmThinkMd\""), "llmThinkMd 线名字段");
    }

    
    @Test
    void 终态回写_PUT路径与status载荷契约() {
        CoreGateway gateway = new CoreGateway(
                "http://localhost:" + server.getAddress().getPort());
        gateway.updateExploreStatus(606L, 2);
        assertEquals(List.of("PUT /internal/explore/sessions/606/status"), STATUS_CALLS,
                "终态必须 PUT 且路径锁定（core internal 契约）");
        assertTrue(STATUS_BODIES.get(0).contains("\"status\":2"),
                "载荷线名=core ExploreStatusUpdate 契约，实际=" + STATUS_BODIES.get(0));
    }

    
    @Test
    void 回写运行态统计_PUT路径与载荷字段契约() {
        CoreGateway gateway = new CoreGateway(
                "http://localhost:" + server.getAddress().getPort());
        gateway.updateExploreStats(303L, new ExploreRunStatsUpdate(7, 88L, "# 报告"));
        assertEquals(List.of("PUT /internal/explore/sessions/303/stats"), STATS_CALLS,
                "回写必须 PUT 且路径锁定（core internal 契约）");
        assertTrue(STATS_BODIES.get(0).contains("\"pagesVisited\":7"),
                "载荷 JSON 线名字=common ExploreRunStatsUpdate 契约，实际=" + STATS_BODIES.get(0));
        assertTrue(STATS_BODIES.get(0).contains("\"costCents\":88"), "costCents 线名字");
        assertTrue(STATS_BODIES.get(0).contains("\"reportMd\""), "reportMd 线名字");
    }
}
