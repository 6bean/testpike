package com.sixbean.testpike.api.mcp.infrastructure.remote;

import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class RestCoreConfigClientTest {

    private StubCoreHttpServer core;
    private RestCoreConfigClient client;

    @BeforeEach
    void startStub() throws IOException {
        core = StubCoreHttpServer.start();
        client = new RestCoreConfigClient(core.baseUrl());
    }

    @AfterEach
    void stopStub() {
        core.close();
    }

    @Test
    void readsValueWithEncodedKeyAndDefault() {
        core.enqueue(StubCoreHttpServer.ok("\"true\""));

        assertEquals("true", client.value("mcp.readonly", "false"));

        StubCoreHttpServer.RecordedCall call = core.call(0);
        assertEquals("GET", call.method());
        assertEquals("/internal/configs/value", call.path());
        assertTrue(call.query().contains("key=mcp.readonly"), call.query());
        assertTrue(call.query().contains("defaultValue=false"), call.query());
    }

    @Test
    void booleanSupplierReadsFreshEveryCall() {
        core.enqueue(StubCoreHttpServer.ok("\"true\""));
        core.enqueue(StubCoreHttpServer.ok("\"false\""));

        assertTrue(client.readonlyFlag().getAsBoolean());
        assertFalse(client.readonlyFlag().getAsBoolean(), "热更语义=每次现读，禁快照缓存");
        assertEquals(2, core.callCount());
    }

    @Test
    void rateLimitFallsBackToDefaultOnGarbageValue() {
        core.enqueue(StubCoreHttpServer.ok("\"abc\""));
        assertEquals(30, client.rateLimitPerMinute().getAsInt(), "非法值回退 30（mcp 键组 V42 seed 同值）");
    }

    @Test
    void rateLimitFallsBackToDefaultWhenCoreUnavailable() {
        core.setDefaultResponse(StubCoreHttpServer.fail("50000", "core 内部异常"));
        int fallback = client.rateLimitPerMinute().getAsInt();
        assertEquals(30, fallback);

        core.enqueue(StubCoreHttpServer.ok("\"90\""));
        assertEquals(90, client.rateLimitPerMinute().getAsInt());
    }

    @Test
    void readonlyFallsBackFalseWhenCoreFailsClosedOnValueIsFalseNotFailOpen() {
        core.enqueue(StubCoreHttpServer.fail("50000", "core 内部异常"));
        assertFalse(client.readonlyFlag().getAsBoolean(),
                "读取失败回退 false=默认非只读态（开关语义 fail-open 读面/fail-closed 无放行写面扩张）");
    }

    @Test
    void explicitValueReadPropagatesFailureAsServiceException() {
        core.enqueue(StubCoreHttpServer.fail("50000", "core 内部异常"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> client.value("mcp.rate-limit/min", null));
        assertTrue(ex.getMessage().contains("core"), ex.getMessage());
    }

    @Test
    void genCapSupplierReadsKeyLiteralAndFallsBack() {
        core.enqueue(StubCoreHttpServer.ok("\"50\""));
        assertEquals(50L, client.genMaxRequirements().getAsLong());
        StubCoreHttpServer.RecordedCall call = core.call(0);
        assertTrue(call.query().contains("key=mcp.gen.max-requirements"), call.query());
        assertTrue(call.query().contains("defaultValue=200"), call.query());

        core.enqueue(StubCoreHttpServer.ok("\"0\""));
        assertEquals(200L, client.genMaxRequirements().getAsLong(),
                "non-positive values fall back to the shipped default");

        core.setDefaultResponse(StubCoreHttpServer.fail("50000", "core internal error"));
        assertEquals(200L, client.genMaxRequirements().getAsLong(),
                "unreadable config falls back to the shipped default without throwing");
    }

    @Test
    void genCapSupplierIsReadFreshOnEveryCall() {
        core.enqueue(StubCoreHttpServer.ok("\"200\""));
        core.enqueue(StubCoreHttpServer.ok("\"5\""));

        assertEquals(200L, client.genMaxRequirements().getAsLong());
        assertEquals(5L, client.genMaxRequirements().getAsLong(),
                "hot-update semantics: re-read per call, no startup snapshot");
        assertEquals(2, core.callCount());
    }
}
