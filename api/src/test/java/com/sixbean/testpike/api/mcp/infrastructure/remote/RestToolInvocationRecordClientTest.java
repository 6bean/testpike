package com.sixbean.testpike.api.mcp.infrastructure.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class RestToolInvocationRecordClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private StubCoreHttpServer core;
    private RestToolInvocationRecordClient client;

    @BeforeEach
    void startStub() throws IOException {
        core = StubCoreHttpServer.start();
        client = new RestToolInvocationRecordClient(core.baseUrl());
    }

    @AfterEach
    void stopStub() {
        core.close();
    }

    @Test
    void postsInvocationBatch_toCoreInternalEndpoint_callerMcpShape() throws Exception {
        core.enqueue(StubCoreHttpServer.ok("1"));

        client.record(List.of(new McpInvocationRemoteInput(
                "mcp", 42L, null, null, null, "create_case", 1, 88L, null, "{\"title\":\"t\"}")));

        StubCoreHttpServer.RecordedCall call = core.call(0);
        assertEquals("POST", call.method());
        assertEquals("/internal/mcp-servers/invocations", call.path());
        JsonNode body = mapper.readTree(call.body());
        assertTrue(body.isArray() && body.size() == 1, "批量体=数组");
        JsonNode item = body.get(0);
        assertEquals("mcp", item.get("caller").asText());
        assertEquals(42L, item.get("actorId").asLong());
        assertTrue(item.get("taskId").isNull());
        assertEquals("create_case", item.get("toolName").asText());
        assertEquals(1, item.get("success").asInt());
        assertEquals(88L, item.get("durationMs").asLong());
        assertEquals("{\"title\":\"t\"}", item.get("digest").asText(), "digest 传原文（core 归一）");
    }

    @Test
    void emptyBatch_zeroOutboundCall() {
        client.record(List.of());
        client.record(null);
        assertEquals(0, core.callCount(), "空批零外呼");
    }

    @Test
    void coreBusinessFailure_propagatesServiceException() {
        core.setDefaultResponse(StubCoreHttpServer.fail("40001", "留痕形状非法"));
        try {
            client.record(List.of(new McpInvocationRemoteInput(
                    "mcp", null, null, null, null, "tag_case", 1, null, null, null)));
            org.junit.jupiter.api.Assertions.fail("core 业务失败必须抛人话（本层禁降级）");
        } catch (com.sixbean.testpike.common.exception.ServiceException ex) {
            assertTrue(ex.getMessage().contains("留痕形状非法"), ex.getMessage());
        }
    }

    @Test
    void failureRow_keepsErrorAndNullDigest() throws Exception {
        core.enqueue(StubCoreHttpServer.ok("1"));
        client.record(List.of(new McpInvocationRemoteInput(
                "mcp", 7L, null, null, null, "get_cases", 0, 12L, "core 不可用", null)));
        JsonNode item = mapper.readTree(core.call(0).body()).get(0);
        assertEquals(0, item.get("success").asInt());
        assertEquals("core 不可用", item.get("error").asText());
        assertNull(item.get("digest") == null || item.get("digest").isNull() ? null : "x",
                "digest 缺省不序列化出空串（可空=未拿到响应无摘要）");
        assertFalse(item.has("digest") && item.get("digest").asText().isEmpty());
    }
}
