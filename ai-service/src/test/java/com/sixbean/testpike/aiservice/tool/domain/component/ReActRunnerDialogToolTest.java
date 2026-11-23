package com.sixbean.testpike.aiservice.tool.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.LlmToolCall;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dialog-surface built-in tool wiring : the live
 * dialog streaming path gets the synthetic aggregate.query tool appended to
 * the assembled plan even when the project's enabled tool set is empty, and
 * a tool_call for it routes through the built-in executor (core query-spec door) instead
 * of the dispatcher's registered-tool index.
 * <p>Non-streaming {@code run} (generation tasks) must stay byte-identical:
 * zero injection, zero plan change — pinned by the no-tools assertion on the
 * plain path.
 * <p>Shares {@link ReActStreamHarness} with the other streaming specs: provider
 * chain mocked, assembly/dispatch/aggregate real, core door = local HTTP stub.
 */
class ReActRunnerDialogToolTest {

    private static HttpServer server;
    private static String baseUrl;
    private static final AtomicReference<String> lastRunBody = new AtomicReference<>();

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ReActRunnerDialogToolTest::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopStub() {
        server.stop(0);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body;
        if ("/internal/query-spec/run".equals(path)) {
            lastRunBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            body = "{\"code\":\"0\",\"msg\":\"ok\",\"data\":{\"target\":\"test_case\","
                    + "\"columns\":[\"status\",\"cnt\"],"
                    + "\"rows\":[{\"status\":1,\"cnt\":7}],\"rowLimitUsed\":200}}";
        } else if ("/internal/query-spec/targets".equals(path)) {
            body = "{\"code\":\"0\",\"msg\":\"ok\",\"data\":["
                    + "{\"targetKey\":\"test_case\",\"dimensionColumns\":\"status\","
                    + "\"measureColumns\":\"\",\"maxRows\":200}]}";
        } else if (path.startsWith("/internal/tools/project/")) {
            body = "{\"code\":\"0\",\"msg\":\"ok\",\"data\":[]}";
        } else {
            body = "{\"code\":\"40401\",\"msg\":\"未知门\",\"data\":null}";
        }
        byte[] resp = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, resp.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(resp);
        }
    }

    private ReActStreamHarness fx;
    private ReActRecordingSink sink;

    @BeforeEach
    void setUp() {
        fx = new ReActStreamHarness(new CoreGateway(baseUrl));
        sink = new ReActRecordingSink();
    }

    @Test
    void dialogStreamInjectsAggregateToolEvenWithEmptyProjectAssembly() {
        fx.script(List.of(), fx.finalAnswer("统计完成"));

        fx.runner().runStreaming(fx.baseRequest("每个状态的用例有多少条"),
                fx.projectId, null, fx.actorId, null, null, sink);

        ArgumentCaptor<ChatCompletionRequest> req =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(fx.chain).chatStreamHandle(req.capture(), anyLong(), any(), any());
        assertNotNull(req.getValue().tools(),
                "empty core list must no longer mean 'no tools' on the dialog surface");
        assertTrue(req.getValue().tools().stream()
                        .anyMatch(t -> AggregateQueryTool.NAME.equals(t.function().name())),
                req.getValue().tools().toString());
    }

    @Test
    void aggregateToolCallRoutesToTheCoreQueryDoorNotTheDispatcher() {
        fx.script(List.of(), fx.withTools(List.of(new LlmToolCall("c1",
                AggregateQueryTool.NAME,
                "{\"target\":\"test_case\",\"groupBy\":[\"status\"],"
                        + "\"aggregations\":[{\"function\":\"count\",\"column\":null}]}"))));
        fx.script(List.of(), fx.finalAnswer("共 7 条评审中"));

        ReActOutcome outcome = fx.runner().runStreaming(fx.baseRequest("每个状态的用例有多少条"),
                fx.projectId, null, fx.actorId, null, null, sink);

        assertNotNull(lastRunBody.get(), "the call must reach /internal/query-spec/run");
        assertTrue(lastRunBody.get().contains("\"groupBy\":[\"status\"]"), lastRunBody.get());
        assertEquals(1, outcome.dispatched());
        assertEquals(0, outcome.rejected());
        // The tool body rides back to the model AND to the sink (chart source).
        assertTrue(sink.log.stream().anyMatch(l -> l.startsWith(
                "tool_done:" + AggregateQueryTool.NAME + ":true")
                && l.contains("\"cnt\":7")), sink.log.toString());
    }

    @Test
    void plainGenerationRunKeepsZeroInjection() {
        // Non-streaming (no sink) = generation-task surface: the historical
        // empty-plan single-call semantics must not drift.
        LlmChatResult plain = fx.finalAnswer("普通补全");
        when(fx.chain.chatHandle(any(), anyLong(), any()))
                .thenReturn(new LlmChatHandle(plain, fx.endpoint));

        fx.runner().run(fx.baseRequest("每个状态的用例有多少条"),
                fx.projectId, null, fx.actorId, null, null);

        ArgumentCaptor<ChatCompletionRequest> req =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(fx.chain).chatHandle(req.capture(), anyLong(), any());
        assertTrue(req.getValue().tools() == null || req.getValue().tools().isEmpty(),
                "aggregate.query is a dialog-surface tool; the plain chain stays untouched");
    }
}
