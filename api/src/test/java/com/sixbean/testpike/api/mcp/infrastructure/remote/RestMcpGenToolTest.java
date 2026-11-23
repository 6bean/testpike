package com.sixbean.testpike.api.mcp.infrastructure.remote;

import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Assembly-layer tests for the MCP generation trigger (gen.cases_from_requirement).
 * <p>Same stub discipline as RestMcpToolGatewayTest: the object under test is the
 * gateway's own URL/method/body/operator-header assembly against the existing
 * core doors (GET /internal/requirements/{id}, POST /internal/ai-scenario-previews,
 * POST /internal/ai-batches). No second generator surface is invented here, so the
 * test asserts the chain rides exactly those doors, in order, zero-write on refusal.
 */
class RestMcpGenToolTest {

    private static final long OPERATOR = 4242L;

    private StubCoreHttpServer core;
    private RestMcpToolGateway gateway;

    @BeforeEach
    void startStub() throws IOException {
        core = StubCoreHttpServer.start();
        gateway = new RestMcpToolGateway(core.baseUrl());
    }

    @AfterEach
    void stopStub() {
        core.close();
    }

    /** Gateway variant with a pinned cap seam (same shape as the doc byte ceiling). */
    private RestMcpToolGateway gatewayWithGenCap(long cap) {
        return new RestMcpToolGateway(core.baseUrl(),
                () -> RestMcpToolGateway.DEFAULT_DOC_MAX_BYTES, () -> cap);
    }

    @Test
    void genCasesPlainModeResolvesRequirementsAndSubmitsOneBatchHop() {
        core.enqueue(StubCoreHttpServer.ok("null")); // membership gate passes
        core.enqueue(StubCoreHttpServer.ok("{\"id\":1,\"projectId\":11,\"title\":\"登录限流\"}"));
        core.enqueue(StubCoreHttpServer.ok("{\"id\":2,\"projectId\":11,\"title\":\"退款幂等\"}"));
        core.enqueue(StubCoreHttpServer.ok("{\"batchId\":9001}"));

        String result = gateway.invoke("gen.cases_from_requirement", Map.of(
                "project_id", 11,
                "requirement_ids", List.of(1, 2),
                "mode", "plain"), McpCaller.unrestricted(OPERATOR));

        assertEquals(4, core.callCount(), "guard + 2 requirement reads + exactly one batch hop");
        StubCoreHttpServer.RecordedCall batch = core.call(3);
        assertEquals("POST", batch.method());
        assertEquals("/internal/ai-batches", batch.path());
        assertEquals(String.valueOf(OPERATOR), batch.lastOperatorHeader());
        assertTrue(batch.body().contains("\"projectId\":11"), batch.body());
        assertTrue(batch.body().contains("\"requirementId\":1"), "items must carry requirement links: " + batch.body());
        assertTrue(batch.body().contains("登录限流"), "items must carry the resolved requirement text: " + batch.body());
        assertTrue(result.contains("\"task_id\":9001"), "receipt returns the batch handle: " + result);
    }

    @Test
    void genCasesDefaultScenarioModeRunsStage0PreviewPerLineBeforeBatch() {
        core.enqueue(StubCoreHttpServer.ok("null"));
        core.enqueue(StubCoreHttpServer.ok("{\"id\":1,\"projectId\":11,\"title\":\"登录限流\"}"));
        core.enqueue(StubCoreHttpServer.ok("{\"id\":2,\"projectId\":11,\"title\":\"退款幂等\"}"));
        core.enqueue(StubCoreHttpServer.ok("501"));
        core.enqueue(StubCoreHttpServer.ok("502"));
        core.enqueue(StubCoreHttpServer.ok("{\"batchId\":9002}"));

        String result = gateway.invoke("gen.cases_from_requirement", Map.of(
                "project_id", 11,
                "requirement_ids", List.of(1, 2)), McpCaller.unrestricted(OPERATOR));

        assertEquals(6, core.callCount(), "guard + 2 reads + 2 previews + 1 batch hop");
        StubCoreHttpServer.RecordedCall preview = core.call(3);
        assertEquals("POST", preview.method());
        assertEquals("/internal/ai-scenario-previews", preview.path());
        assertTrue(preview.body().contains("登录限流"), "preview carries the requirement text: " + preview.body());
        assertTrue(preview.body().contains("\"seq\":0"), "preview rows follow submission order: " + preview.body());
        assertEquals("/internal/ai-batches", core.call(5).path());
        assertTrue(result.contains("\"task_id\":9002"), result);
    }

    @Test
    void genCasesDryRunResolvesLinesButNeverEnqueues() {
        core.enqueue(StubCoreHttpServer.ok("null"));
        core.enqueue(StubCoreHttpServer.ok("{\"id\":1,\"projectId\":11,\"title\":\"登录限流\"}"));
        core.enqueue(StubCoreHttpServer.ok("{\"id\":7,\"projectId\":11,\"title\":\"退款幂等\"}"));

        String result = gateway.invoke("gen.cases_from_requirement", Map.of(
                "project_id", 11,
                "requirement_ids", List.of(1, 7),
                "mode", "plain",
                "dry_run", true), McpCaller.unrestricted(OPERATOR));

        assertEquals(3, core.callCount(), "preflight is read-only: zero preview hops, zero batch hop");
        assertTrue(result.contains("\"dry_run\":true"), result);
        assertTrue(result.contains("\"requirement_count\":2"), result);
    }

    @Test
    void genCasesOverCapRefusesNamingCurrentCountAndGovernanceKey() {
        RestMcpToolGateway capped = gatewayWithGenCap(2);
        core.enqueue(StubCoreHttpServer.ok("null"));

        ServiceException ex = assertThrows(ServiceException.class, () -> capped.invoke(
                "gen.cases_from_requirement", Map.of(
                        "project_id", 11,
                        "requirement_ids", List.of(1, 2, 3),
                        "mode", "plain"), McpCaller.unrestricted(OPERATOR)));

        assertTrue(ex.getMessage().contains("mcp.gen.max-requirements"),
                "refusal must name the governance key: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("3"), "refusal must name the current count: " + ex.getMessage());
        assertEquals(1, core.callCount(), "cap check lands before any read or batch hop");
    }

    @Test
    void genCasesWithoutRequirementIdsRefusesBeforeAnyCoreHop() {
        ServiceException ex = assertThrows(ServiceException.class, () -> gateway.invoke(
                "gen.cases_from_requirement", Map.of(
                        "project_id", 11,
                        "requirement_ids", List.of()), McpCaller.unrestricted(OPERATOR)));
        assertTrue(ex.getMessage().contains("需求点"), ex.getMessage());
        assertEquals(0, core.callCount(), "empty selection writes nothing and calls nothing");
    }

    @Test
    void genCasesRefusesForeignProjectBeforeAnyBusinessHop() {
        core.enqueue(StubCoreHttpServer.fail("40301", "无权访问该项目"));

        ServiceException ex = assertThrows(ServiceException.class, () -> gateway.invoke(
                "gen.cases_from_requirement", Map.of(
                        "project_id", 99,
                        "requirement_ids", List.of(1),
                        "mode", "plain"), McpCaller.unrestricted(OPERATOR)));

        assertTrue(ex.getMessage().contains("无权访问该项目"), ex.getMessage());
        assertEquals(1, core.callCount(), "membership gate first: zero requirement reads, zero batch hop");
    }

    @Test
    void genCasesUnknownModeRefusesNamingAllowedValues() {
        core.enqueue(StubCoreHttpServer.ok("null"));

        ServiceException ex = assertThrows(ServiceException.class, () -> gateway.invoke(
                "gen.cases_from_requirement", Map.of(
                        "project_id", 11,
                        "requirement_ids", List.of(1),
                        "mode", "turbo"), McpCaller.unrestricted(OPERATOR)));

        assertTrue(ex.getMessage().contains("scenario") && ex.getMessage().contains("plain"),
                "refusal must list the allowed modes: " + ex.getMessage());
        assertEquals(1, core.callCount());
    }
}
