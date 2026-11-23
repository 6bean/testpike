package com.sixbean.testpike.api.cluster.infrastructure.remote.client;

import com.sixbean.testpike.api.mcp.infrastructure.remote.StubCoreHttpServer;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class RestCoreClusterEnrollClientTest {

    
    private static final String SHA_ABC =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    private StubCoreHttpServer core;
    private RestCoreClusterEnrollClient client;

    @BeforeEach
    void startCore() throws IOException {
        core = StubCoreHttpServer.start();
        client = new RestCoreClusterEnrollClient(core.baseUrl());
    }

    @AfterEach
    void stopCore() {
        core.close();
    }

    @Test
    void poll口_明文令牌折摘要后转发_明文零残留() {
        core.enqueue(StubCoreHttpServer.ok("[]"));
        Map<String, Object> body = new HashMap<>();
        body.put("token", "  abc ");
        body.put("fingerprint", "fp-01");
        body.put("limit", 3);
        client.pollCommand(body);
        var call = core.call(0);
        assertEquals("POST", call.method());
        assertEquals("/internal/cluster/commands/poll", call.path());
        assertTrue(call.body().contains("\"tokenDigest\":\"" + SHA_ABC + "\""),
                "api 层必把明文令牌折成 trim 后 sha256 摘要: " + call.body());
        assertFalse(call.body().contains("abc") && !call.body().contains(SHA_ABC),
                "明文令牌禁残留转发体（红线 4）: " + call.body());
        assertFalse(call.body().contains("\"token\":\""), "token 明文键必须摘除: " + call.body());
        assertTrue(call.body().contains("fp-01"), "指纹原样透传: " + call.body());
        assertTrue(call.body().contains("\"limit\":3"), "limit 原样透传: " + call.body());
    }

    @Test
    void poll口_executorId直领形态_零折键透传() {
        core.enqueue(StubCoreHttpServer.ok("[]"));
        Map<String, Object> body = new HashMap<>();
        body.put("executorId", 9);
        body.put("limit", 1);
        client.pollCommand(body);
        var call = core.call(0);
        assertFalse(call.body().contains("tokenDigest"), "无令牌形态禁注入摘要键: " + call.body());
        assertTrue(call.body().contains("\"executorId\":9"), call.body());
    }

    @Test
    void poll口_成功信封列表解析透传() {
        core.enqueue(StubCoreHttpServer.ok(
                "[{\"id\":12,\"commandType\":\"engine-install\",\"status\":1}]"));
        List<Map<String, Object>> polled = client.pollCommand(
                Map.of("token", "abc", "fingerprint", "fp"));
        assertEquals(1, polled.size());
        assertEquals("engine-install", polled.get(0).get("commandType"));
    }

    @Test
    void progress口_URL与体透传_Void信封零炸() {
        core.enqueue(StubCoreHttpServer.ok("null"));
        client.progressCommand(Map.of("commandId", 12, "claimerId", 3,
                "step", "install-npm", "message", "装引擎中"));
        var call = core.call(0);
        assertEquals("POST", call.method());
        assertEquals("/internal/cluster/commands/progress", call.path());
        assertTrue(call.body().contains("install-npm"), call.body());
    }

    @Test
    void result口_URL与体透传() {
        core.enqueue(StubCoreHttpServer.ok("null"));
        client.completeCommand(Map.of("commandId", 12, "claimerId", 3,
                "success", true, "resultDetail", "装毕"));
        var call = core.call(0);
        assertEquals("/internal/cluster/commands/result", call.path());
        assertTrue(call.body().contains("\"success\":true"), call.body());
    }

    @Test
    void progressCommand_foldsPlaintextToken_beforeForwarding() {
        core.enqueue(StubCoreHttpServer.ok("null"));
        client.progressCommand(Map.of("commandId", 12, "claimerId", 3, "step", "ack",
                "token", "sec-tok", "fingerprint", "fp-1"));
        var call = core.call(0);
        assertTrue(call.body().contains("tokenDigest"),
                "progress leg must fold like poll: " + call.body());
        assertFalse(call.body().contains("sec-tok"),
                "plaintext token must never reach the core channel: " + call.body());
        assertFalse(call.body().contains("\"token\":"),
                "plaintext key must be dropped: " + call.body());
        assertTrue(call.body().contains("fp-1"),
                "fingerprint passes through untouched: " + call.body());
    }

    @Test
    void completeCommand_foldsPlaintextToken_beforeForwarding() {
        core.enqueue(StubCoreHttpServer.ok("null"));
        client.completeCommand(Map.of("commandId", 12, "claimerId", 3, "success", true,
                "token", "sec-tok", "fingerprint", "fp-2"));
        var call = core.call(0);
        assertTrue(call.body().contains("tokenDigest"), call.body());
        assertFalse(call.body().contains("sec-tok"),
                "plaintext token must never reach the core channel: " + call.body());
    }

    @Test
    void core业务失败信封_人话ServiceException透传() {
        core.enqueue(StubCoreHttpServer.fail("40001", "领取命令必须携带执行器 id 或入池令牌摘要之一"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> client.pollCommand(Map.of("limit", 1)));
        assertEquals("领取命令必须携带执行器 id 或入池令牌摘要之一", ex.getMessage());
        assertEquals(40001, ex.getCode());
    }

    @Test
    void heartbeat口_step尾追三键_有值才下发() {
        core.enqueue(StubCoreHttpServer.ok("{\"accepted\":true}"));
        client.heartbeat("tk", "fp", "register", "running", "开工打点入池");
        var call = core.call(0);
        assertEquals("POST", call.method());
        assertEquals("/internal/cluster/enroll/heartbeat", call.path());
        assertTrue(call.body().contains("\"step\":\"register\""), call.body());
        assertTrue(call.body().contains("\"stepStatus\":\"running\""), call.body());
        assertTrue(call.body().contains("开工打点入池"), call.body());
        assertTrue(call.body().contains("\"token\":\"tk\""), call.body());
    }

    @Test
    void heartbeat口_旧口径打点_step键零注入() {
        core.enqueue(StubCoreHttpServer.ok("null"));
        client.heartbeat("tk", "fp", null, null, null);
        var call = core.call(0);
        assertFalse(call.body().contains("\"step\""),
                "step=null 禁注入键（core 旧口径零残留）: " + call.body());
        assertFalse(call.body().contains("\"components\""),
                "components=null must not inject the key (old reporters zero drift): " + call.body());
    }

    @Test
    void heartbeat口_components尾追键_原样透传不落空键() {
        // pack C (03 §2.8): agent platform-family rows forward as-is
        // (no re-shaping here; validation and ledger gates live in core);
        // an empty list = key omitted = old reporters zero drift.
        core.enqueue(StubCoreHttpServer.ok("{\"accepted\":true}"));
        client.heartbeat("tk", "fp-a", null, null, null,
                List.of(Map.of("family", "core", "currentVersion", "1.0.0")));
        var call = core.call(0);
        assertEquals("/internal/cluster/enroll/heartbeat", call.path());
        assertTrue(call.body().contains("\"components\""), call.body());
        assertTrue(call.body().contains("\"core\""), call.body());
        assertTrue(call.body().contains("1.0.0"), call.body());

        core.enqueue(StubCoreHttpServer.ok("{\"accepted\":true}"));
        client.heartbeat("tk", "fp-a", null, null, null, List.of());
        assertFalse(core.call(1).body().contains("\"components\""),
                "空清单禁注入键: " + core.call(1).body());
    }

    @Test
    void engineRecipe口_按码GET转发core引擎详情_响应透传() {
        core.enqueue(StubCoreHttpServer.ok(
                "{\"code\":\"mobilewright\",\"installRecipe\":{\"hosts\":{}}}"));
        Map<String, Object> info = client.engineRecipe("mobilewright");
        var call = core.call(0);
        assertEquals("GET", call.method());
        assertEquals("/internal/appauto/engines/mobilewright", call.path());
        assertEquals("mobilewright", info.get("code"));
    }

    @Test
    void enroll既有链路_明文令牌原样下发core核销() {
        core.enqueue(StubCoreHttpServer.ok("{\"tokenId\":5,\"role\":\"executor\"}"));
        Map<String, Object> result = client.enroll("plain-token", "fp-02");
        assertEquals(5, ((Number) result.get("tokenId")).intValue());
        var call = core.call(0);
        assertEquals("/internal/cluster/enroll", call.path());
        assertTrue(call.body().contains("plain-token"), "enroll 口明文照旧: " + call.body());
    }
}
