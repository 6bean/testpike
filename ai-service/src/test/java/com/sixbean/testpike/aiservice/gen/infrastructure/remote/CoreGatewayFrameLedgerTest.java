package com.sixbean.testpike.aiservice.gen.infrastructure.remote;

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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CoreGateway frame-ledger door consumption test :
 * the writer side of the dialog kernel posts turns/frames to the core
 * internal door {@code /internal/ai-chat/**} over real HTTP.
 * <p>Posture identical to {@link CoreGatewayFcAssemblyTest}: RestClient →
 * local core-response stub endpoint (an environment-level stand-in for the
 * core service, not a business mock). Assertions pin the wire contract the
 * core controller expects — path shape, method, JSON body keys — plus the
 * response unwrapping (data id/seq, message info mirror) and the refusal of
 * a business error code (ServiceException surfaces, caller decides).
 */
class CoreGatewayFrameLedgerTest {

    private static HttpServer server;
    private static String lastPath;
    private static String lastMethod;
    private static String lastBody;

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", CoreGatewayFrameLedgerTest::handle);
        server.start();
    }

    @AfterAll
    static void stopStub() {
        server.stop(0);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        lastPath = exchange.getRequestURI().toString();
        lastMethod = exchange.getRequestMethod();
        lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String body = switch (lastPath) {
            case "/internal/ai-chat/sessions/9/messages" ->
                    "{\"code\":\"0\",\"msg\":\"ok\",\"data\":42}";
            case "/internal/ai-chat/sessions/9/frames" ->
                    "{\"code\":\"0\",\"msg\":\"ok\",\"data\":7}";
            case "/internal/ai-chat/sessions/9/last-seq" ->
                    "{\"code\":\"0\",\"msg\":\"ok\",\"data\":7}";
            case "/internal/ai-chat/sessions/9/total-tokens" ->
                    "{\"code\":\"0\",\"msg\":\"ok\",\"data\":512}";
            case "/internal/ai-chat/sessions/9/context?userId=7" ->
                    "{\"code\":\"0\",\"msg\":\"ok\",\"data\":\"{\\\"route\\\":\\\"/cases\\\"}\"}";
            case "/internal/ai-chat/sessions/9/messages?userId=7" ->
                    "{\"code\":\"0\",\"msg\":\"ok\",\"data\":["
                            + "{\"id\":1,\"sessionId\":9,\"role\":\"user\",\"content\":\"昨天的失败原因\","
                            + "\"reasoning\":null,\"status\":\"done\",\"seqFrom\":1,\"seqTo\":1,"
                            + "\"promptTokens\":3,\"completionTokens\":0},"
                            + "{\"id\":2,\"sessionId\":9,\"role\":\"assistant\",\"content\":\"以超时为主\","
                            + "\"reasoning\":null,\"status\":\"done\",\"seqFrom\":2,\"seqTo\":4,"
                            + "\"promptTokens\":10,\"completionTokens\":20}]}";
            case "/internal/ai-chat/messages/42/close" ->
                    messageInfo("done");
            case "/internal/ai-chat/messages/42/abort" ->
                    messageInfo("aborted");
            case "/internal/ai-chat/sessions/99/frames" ->
                    "{\"code\":\"40001\",\"msg\":\"未知的帧类型：nope，合法值=[state, reason]\",\"data\":null}";
            default -> "{\"code\":\"0\",\"msg\":\"ok\",\"data\":null}";
        };
        byte[] resp = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, resp.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(resp);
        }
    }

    private static String messageInfo(String status) {
        return "{\"code\":\"0\",\"msg\":\"ok\",\"data\":{\"id\":42,\"sessionId\":9,"
                + "\"role\":\"assistant\",\"content\":\"分析完成\",\"reasoning\":null,"
                + "\"status\":\"" + status + "\",\"seqFrom\":1,\"seqTo\":7,"
                + "\"promptTokens\":10,\"completionTokens\":20}}";
    }

    private static CoreGateway gateway() {
        return new CoreGateway("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    void sessionTotalTokens_getsTheLedgerDoorAndUnwrapsTheNumber() {
        assertEquals(512L, gateway().sessionTotalTokens(9L));
        assertEquals("GET", lastMethod);
        assertEquals("/internal/ai-chat/sessions/9/total-tokens", lastPath);
    }

    @Test
    void openChatMessage_postsRoleAndContentAndReturnsMessageId() {
        long messageId = gateway().openChatMessage(9L, "assistant", null);
        assertEquals("POST", lastMethod);
        assertEquals("/internal/ai-chat/sessions/9/messages", lastPath);
        assertTrue(lastBody.contains("\"role\":\"assistant\""), "role must cross the wire: " + lastBody);
        assertEquals(42L, messageId);
    }

    @Test
    void appendChatFrame_postsWireNameStringPayloadAndMetaAndReturnsSeq() {
        long seq = gateway().appendChatFrame(9L, 42L, "delta", "分析", "{\"phase\":\"x\"}");
        assertEquals("POST", lastMethod);
        assertEquals("/internal/ai-chat/sessions/9/frames", lastPath);
        assertTrue(lastBody.contains("\"messageId\":42"), lastBody);
        assertTrue(lastBody.contains("\"frameType\":\"delta\""), lastBody);
        assertTrue(lastBody.contains("\"payload\":\"分析\""), lastBody);
        assertTrue(lastBody.contains("\"metaJson\":\"{\\\"phase\\\":\\\"x\\\"}\""), lastBody);
        assertEquals(7L, seq);
    }

    @Test
    void lastChatSeq_readsSessionCursorOverGet() {
        long seq = gateway().lastChatSeq(9L);
        assertEquals("GET", lastMethod);
        assertEquals("/internal/ai-chat/sessions/9/last-seq", lastPath);
        assertEquals(7L, seq);
    }

    /**
 * a read door: the dialog-history GET returns sealed turns
 * oldest first and the mirror list parses role/content pairs — the rolling
 * assembly consumes exactly this door (no second history chain).
 */
    /**
 * a2 pinned-context read door: GET context?userId= unwraps
 * the Response envelope to the raw whitelisted JSON string the write door
 * stored — the renderer, not the gateway, decides which keys become facts.
 */
    @Test
    void sessionContext_readsPinnedContextDoorAndUnwrapsTheStringPayload() {
        String context = gateway().sessionContext(7L, 9L);

        assertEquals("GET", lastMethod);
        assertEquals("/internal/ai-chat/sessions/9/context?userId=7", lastPath);
        assertEquals("{\"route\":\"/cases\"}", context,
                "the data string lands verbatim for the read-side renderer");
    }

    @Test
    void listChatMessages_readsSealedHistoryDoorAndParsesTheMirrorList() {
        java.util.List<ChatMessageRemote> history = gateway().listChatMessages(7L, 9L);
        assertEquals("GET", lastMethod);
        assertEquals("/internal/ai-chat/sessions/9/messages?userId=7", lastPath);
        assertEquals(2, history.size(), "both sealed turns cross the door");
        assertEquals("user", history.get(0).role());
        assertEquals("昨天的失败原因", history.get(0).content());
        assertEquals("assistant", history.get(1).role());
        assertEquals("以超时为主", history.get(1).content());
        assertEquals("done", history.get(1).status());
    }

    @Test
    void closeChatMessage_sealsTurnAndParsesMessageMirror() {
        ChatMessageRemote info = gateway().closeChatMessage(42L, "done");
        assertEquals("POST", lastMethod);
        assertEquals("/internal/ai-chat/messages/42/close", lastPath);
        assertTrue(lastBody.contains("\"status\":\"done\""), lastBody);
        assertEquals(42L, info.id());
        assertEquals(9L, info.sessionId());
        assertEquals("done", info.status());
        assertEquals(1L, info.seqFrom());
        assertEquals(7L, info.seqTo());
    }

    @Test
    void abortChatMessage_keepsPartialHonestInMirror() {
        ChatMessageRemote info = gateway().abortChatMessage(42L);
        assertEquals("POST", lastMethod);
        assertEquals("/internal/ai-chat/messages/42/abort", lastPath);
        assertEquals("aborted", info.status());
        assertEquals("分析完成", info.content(), "partial body from the ledger stays visible");
    }

    @Test
    void illegalFrameType_surfacesAsServiceExceptionForTheCaller() {
        Map<String, Object> ignored = null;
        ServiceException ex = assertThrows(ServiceException.class,
                () -> gateway().appendChatFrame(99L, 1L, "nope", "x", null));
        assertTrue(ex.getMessage().contains("未知的帧类型"), ex.getMessage());
    }
}