package com.sixbean.testpike.aiservice.tool.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.common.util.DesensitizeUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Retrieval meta-tool unit test . L2 isolates infrastructure
 * with a JDK HttpServer stub mirroring the core view door plus a blob sink:
 * the pins, in order — a task-scope anchor is only readable inside its owning
 * task (cross-task read is refused BEFORE any storage hop and journaled); a
 * dialog-scope anchor is only readable in the dialog context (taskId null —
 * session-level isolation is a documented gap until a dialog id reaches
 * ReActRunner); malformed/foreign-scene anchors never touch the door; the
 * fetched full text comes back desensitized through the existing
 * {@link DesensitizeUtils} single source; and a missing object or refused
 * grant degrades to a readable refusal instead of breaking the dialog chain.
 */
class ToolResultRetrievalToolTest {

    private static final String TASK_ANCHOR =
            "testpike/0/tool-result/202611/abcd1234-task42-ff.txt";
    private static final String DIALOG_ANCHOR =
            "testpike/0/tool-result/202611/beef5678-dialog-9a.txt";
    private static final String FULL_TEXT =
        "工具全文头部。联系方式 13800138000 与 sample.user@example.com。尾部锚点 TAIL-9527。";

    private static HttpServer server;
    private static final List<String> VIEW_CALLS = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/internal/ai-chat/tool-artifact/view", exchange -> {
            VIEW_CALLS.add(URLDecoder.decode(
                    exchange.getRequestURI().toString(), StandardCharsets.UTF_8));
            String key = exchange.getRequestURI().getQuery() == null ? ""
                    : exchange.getRequestURI().getQuery();
            String base = "http://localhost:" + server.getAddress().getPort();
            String blob = base + "/blob?" + key;
            writeJson(exchange, "{\"code\":\"0\",\"msg\":\"ok\",\"data\":\"" + blob + "\"}");
        });
        server.createContext("/blob", exchange -> {
            if (exchange.getRequestURI().toString().contains("task42-ff")
                    || exchange.getRequestURI().toString().contains("dialog-9a")) {
                byte[] body = FULL_TEXT.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            } else {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            }
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
        VIEW_CALLS.clear();
    }

    private static void writeJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private ToolResultRetrievalTool tool() {
        return new ToolResultRetrievalTool(
                new CoreGateway("http://localhost:" + server.getAddress().getPort()),
                new ObjectMapper(), RestClient.builder());
    }

    private static String args(String anchor) {
        return "{\"anchor\":\"" + anchor + "\"}";
    }

    @Test
    void taskAnchorWithinOwningTaskReturnsDesensitizedFullText() {
        ToolCallOutcome outcome = tool().execute(args(TASK_ANCHOR), 42L, 7L);
        assertTrue(outcome.success(), "owning task must retrieve its own dump, actual="
                + outcome.content());
        assertTrue(outcome.content().contains("TAIL-9527"), "tail text rides through");
        assertFalse(outcome.content().contains("13800138000"),
                "phone must be masked by the DesensitizeUtils single source");
        assertFalse(outcome.content().contains("sample.user@example.com"),
                "email must be masked by the same source");
        assertTrue(outcome.content().contains(DesensitizeUtils.maskText(FULL_TEXT).substring(0, 6)),
                "body is exactly the maskText output, no second desensitization path");
        assertEquals(1, VIEW_CALLS.size(), "view door asked once through the core route");
        assertTrue(VIEW_CALLS.get(0).contains("objectKey=" + TASK_ANCHOR),
                "canonical anchor key forwarded verbatim, actual=" + VIEW_CALLS.get(0));
    }

    @Test
    void crossTaskAnchorIsRefusedBeforeAnyStorageHop() {
        ToolCallOutcome outcome = tool().execute(args(TASK_ANCHOR), 43L, 7L);
        assertFalse(outcome.success(), "task 43 must not read task 42's dump");
        assertTrue(outcome.content().contains("42"),
                "refusal names the owning task so the model can re-anchor");
        assertTrue(VIEW_CALLS.isEmpty(), "cross-task read must never reach the storage door");
    }

    @Test
    void taskAnchorFromDialogContextIsRefused() {
        ToolCallOutcome outcome = tool().execute(args(TASK_ANCHOR), null, 7L);
        assertFalse(outcome.success(), "dialog context has no task identity to match");
        assertTrue(VIEW_CALLS.isEmpty());
    }

    @Test
    void dialogAnchorIsReadableOnlyInDialogContext() {
        assertTrue(tool().execute(args(DIALOG_ANCHOR), null, null).success(),
                "dialog-scope anchor belongs to the dialog surface (taskId null)");
        ToolCallOutcome inTask = tool().execute(args(DIALOG_ANCHOR), 42L, 7L);
        assertFalse(inTask.success(), "a generation task must not read dialog-scope dumps");
    }

    @Test
    void malformedOrForeignAnchorIsRefusedWithoutStorageHop() {
        for (String bad : List.of("exec/3/png/abcd1234-shot.png",
                "testpike/0/judge/202611/abcd1234-run-1-sample-2.json",
                "testpike/0/tool-result/202611/noscope-xyz.txt")) {
            ToolCallOutcome outcome = tool().execute(args(bad), 42L, 7L);
            assertFalse(outcome.success(), "anchor must be refused: " + bad);
        }
        assertTrue(VIEW_CALLS.isEmpty(), "shape check precedes the door");
    }

    @Test
    void blankArgsAndMissingAnchorFieldRefused() {
        assertFalse(tool().execute(null, 42L, 7L).success());
        assertFalse(tool().execute("{}", 42L, 7L).success());
        assertFalse(tool().execute("not-json", 42L, 7L).success());
        assertTrue(VIEW_CALLS.isEmpty());
    }

    @Test
    void missingObjectDegradesToReadableRefusalNotException() {
        String gone = "testpike/0/tool-result/202611/abcd1234-task42-gone.txt";
        ToolCallOutcome outcome = tool().execute(args(gone), 42L, 7L);
        assertFalse(outcome.success(), "404 blob must surface as a refusal, never a throw");
        assertFalse(outcome.content().isBlank());
    }
}
