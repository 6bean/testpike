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
 * GA1 piece B: the api MCP gateway threads an {@link McpCaller} through both gate
 * points (requireProjectMember / requireSpaceMember) so a token pinned to an
 * explicit project set can neither write nor read outside that set.
 * Bidirectional fixtures per the adjudicated design:
 * in-scope pass / out-of-scope denial with zero outbound hops / empty pin allows
 * everything (pre-GA1 tokens keep the pure membership door, zero drift) /
 * null normalizes to empty. Denial naming lists the token's current project set.
 */
class McpProjectScopeGateTest {

    private McpGatewayStubHarness h;
    private StubCoreHttpServer core;
    private RestMcpToolGateway gateway;

    @BeforeEach
    void startStub() throws IOException {
        h = McpGatewayStubHarness.open();
        core = h.core();
        gateway = h.gateway();
    }

    @AfterEach
    void stopStub() {
        h.close();
    }

    private static McpCaller pinned(String... projectIds) {
        return new McpCaller(McpGatewayStubHarness.OPERATOR, List.of(projectIds));
    }

    // ===== write tools: the pin rides before any core hop =====

    @Test
    void inScopeWriteStillRidesTheMemberGate() {
        core.enqueue(StubCoreHttpServer.ok("null")); // access-check passes
        core.enqueue(StubCoreHttpServer.ok(
                "{\"id\":777,\"caseNo\":\"TC-DEMO-0001\",\"projectId\":11,\"title\":\"t\",\"status\":1}"));

        gateway.invoke("create_case", Map.of(
                "project_id", 11,
                "title", "t",
                "steps", "s",
                "expected", "e"), pinned("11", "12"));

        assertEquals(2, core.callCount(), "pinned in-scope write: guard hop then create hop");
        assertEquals("/internal/projects/access-check", core.call(0).path());
    }

    @Test
    void outOfScopeWriteIsDeniedBeforeAnyHopAndNamesThePin() {
        ServiceException ex = assertThrows(ServiceException.class, () -> gateway.invoke(
                "create_case", Map.of(
                        "project_id", 12,
                        "title", "t",
                        "steps", "s",
                        "expected", "e"), pinned("11")));

        assertEquals(0, core.callCount(), "denied out-of-scope target must produce zero core hops");
        assertTrue(ex.getMessage().contains("11"), "refusal names the token's current project set: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("12"), "refusal names the rejected target: " + ex.getMessage());
    }

    @Test
    void emptyPinPassesEveryTargetToTheMembershipDoorUnchanged() {
        core.enqueue(StubCoreHttpServer.ok("null"));
        core.enqueue(StubCoreHttpServer.ok(
                "{\"id\":778,\"caseNo\":\"TC-DEMO-0002\",\"projectId\":99,\"title\":\"t\",\"status\":1}"));

        gateway.invoke("create_case", Map.of(
                "project_id", 99,
                "title", "t",
                "steps", "s",
                "expected", "e"), McpCaller.unrestricted(McpGatewayStubHarness.OPERATOR));

        assertEquals(2, core.callCount(),
                "unrestricted caller keeps the exact pre-GA1 door: access-check then create");
    }

    // ===== gate methods reached through resolved rows (space / batch) =====

    @Test
    void spaceGatePinsTheResolvedProjectAndWritesNothing() {
        core.enqueue(StubCoreHttpServer.ok("{\"projectId\":11,\"name\":\"space-11\"}"));

        ServiceException ex = assertThrows(ServiceException.class, () -> gateway.invoke(
                "inspect_mock_hits", Map.of("space_id", 5), pinned("22")));

        assertEquals(1, core.callCount(), "only the space lookup happens; membership door never reached");
        assertTrue(ex.getMessage().contains("22"), "refusal names the pinned set: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("11"), "refusal names the resolved owning project: " + ex.getMessage());
    }

    @Test
    void batchGatePinsTheResolvedProjectAndCancelsNothing() {
        core.enqueue(StubCoreHttpServer.ok("{\"id\":7,\"projectId\":11,\"status\":1}"));

        assertThrows(ServiceException.class, () -> gateway.invoke(
                "exec.cancel", Map.of("batch_id", 7), pinned("22")));

        assertEquals(1, core.callCount(), "batch read only; no access-check, no cancel POST");
    }

    @Test
    void inScopeBatchCancelStillRidesTheFullChain() {
        core.enqueue(StubCoreHttpServer.ok("{\"id\":7,\"projectId\":11,\"status\":1}")); // batch read
        core.enqueue(StubCoreHttpServer.ok("null")); // access-check passes
        core.enqueue(StubCoreHttpServer.ok("{\"id\":7,\"status\":3,\"statusLabel\":\"已取消\"}"));

        gateway.invoke("exec.cancel", Map.of("batch_id", 7), pinned("11"));

        assertEquals(3, core.callCount(), "resolve pin -> membership -> cancel, exactly three hops");
    }

    // ===== read tools whose project arrives as a direct argument =====

    @Test
    void outOfScopeReadIsDeniedBeforeAnyHop() {
        assertThrows(ServiceException.class, () -> gateway.invoke(
                "get_cases", Map.of("project_id", 12), pinned("11")));

        assertEquals(0, core.callCount(), "out-of-scope reads must not reach core at all");
    }

    @Test
    void inScopeReadRidesTheNormalPath() {
        core.enqueue(StubCoreHttpServer.ok("{\"list\":[],\"total\":0}"));

        gateway.invoke("get_cases", Map.of("project_id", 11), pinned("11"));

        assertEquals(1, core.callCount());
    }

    @Test
    void requirementListReadIsPinnedToo() {
        assertThrows(ServiceException.class, () -> gateway.invoke(
                "list_requirements", Map.of("project_id", 12), pinned("11")));

        assertEquals(0, core.callCount());
    }

    // ===== value object normalization (pre-GA1 zero drift) =====

    @Test
    void nullScopeNormalizesToUnrestricted() {
        McpCaller legacy = new McpCaller(McpGatewayStubHarness.OPERATOR, null);

        assertTrue(legacy.projectScope().isEmpty());
        legacy.assertWithinProjectPin(4242L); // no exception: empty pin allows every target
    }

    @Test
    void blankScopeEntriesAreDroppedAndIdsMatchExactly() {
        McpCaller caller = new McpCaller(McpGatewayStubHarness.OPERATOR, java.util.Arrays.asList(" 11 ", "", null));

        assertEquals(List.of("11"), caller.projectScope());
        caller.assertWithinProjectPin(11); // trimmed pin matches the numeric target
    }
}
