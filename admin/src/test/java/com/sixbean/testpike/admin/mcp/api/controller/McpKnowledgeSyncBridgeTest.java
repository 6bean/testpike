package com.sixbean.testpike.admin.mcp.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.mcp.api.dto.KnowledgeSyncRoundView;
import com.sixbean.testpike.admin.mcp.api.dto.KnowledgeSyncUpsertRequest;
import com.sixbean.testpike.admin.mcp.api.dto.KnowledgeSyncView;
import com.sixbean.testpike.admin.mcp.app.component.McpAccessGuard;
import com.sixbean.testpike.admin.mcp.infrastructure.remote.client.McpKnowledgeSyncClient;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.rbac.app.component.PublicEndpointRegistry;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Knowledge sync subscription admin bridge contract (ledger face mirror).
 * <p>Asserted surface: the eight nested legs forward scope, payload and the
 * typed round outcome without rewriting core semantics (every guard, the
 * DOWN probe gate and the failure typing live single-sourced in the core
 * service); the ADMIN entity gate fires before any core interaction; the
 * nested path of a server wins over a stale serverId carried in the body.
 * <p>L2 bridge isolation follows the McpAuthBridgeTest precedent: the remote
 * client and the access guard are stand-ins here because the bridge itself
 * only forwards and gates. The real admin-to-core chain is exercised on the
 * L3 live face, never mocked in delivery claims.
 */
class McpKnowledgeSyncBridgeTest {

    /** Reuses the MCP management key; the bridge introduces no new permission. */
    private static final String KEY = "perm:knowledge:mcp-manage";

    private final McpKnowledgeSyncClient syncClient = mock(McpKnowledgeSyncClient.class);
    private final McpAccessGuard mcpGuard = mock(McpAccessGuard.class);
    private final McpKnowledgeSyncController controller =
            new McpKnowledgeSyncController(syncClient, mcpGuard);

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    private CurrentUser login() {
        CurrentUser user = new CurrentUser(7L, "u", "name", "dept", List.of("ADMIN"), 0L, null);
        UserContext.set(user);
        return user;
    }

    private KnowledgeSyncView row(long id, long serverId) {
        return new KnowledgeSyncView(id, 2L, serverId, "wiki-mcp", "kb-nightly",
                "fetch_page", Map.of("space", "docs"), 60, 2L, 0L,
                false, true, "updated", null, "2026-11-01 01:00:00", 3, 1, 0);
    }

    /** The list leg forwards the nested scope, the optional project filter and paging untouched. */
    @Test
    void listForwardsNestedScopeAndPassesRowsThrough() {
        CurrentUser user = login();
        when(syncClient.list(4L, 2L, 1, 20))
                .thenReturn(new PageView<>(List.of(row(9L, 4L)), 1L));

        PageView<KnowledgeSyncView> result = controller.list(4L, 2L, 1, 20).getData();

        verify(mcpGuard).assertCanManage(user);
        assertEquals(1L, result.total());
        assertEquals("kb-nightly", result.list().get(0).name(), "ledger rows pass through unwrapped");
    }

    /** A missing project filter travels as an absent parameter, never as a literal null. */
    @Test
    void listWithoutProjectFilterForwardsNullScope() {
        login();
        when(syncClient.list(4L, null, 2, 50)).thenReturn(new PageView<>(List.of(), 0L));

        assertEquals(0L, controller.list(4L, null, 2, 50).getData().total());
        verify(syncClient).list(4L, null, 2, 50);
    }

    /** The register leg binds the nested server scope over any stale body value; every other field is forwarded verbatim. */
    @Test
    void createBindsPathServerScopeOverBodyValue() {
        login();
        KnowledgeSyncUpsertRequest body = new KnowledgeSyncUpsertRequest(
                2L, 99L, "kb-nightly", "fetch_page", Map.of("space", "docs"),
                60, 2L, 0L, null);
        when(syncClient.save(any())).thenReturn(row(9L, 4L));

        KnowledgeSyncView result = controller.create(4L, body).getData();

        ArgumentCaptor<KnowledgeSyncUpsertRequest> sent =
                ArgumentCaptor.forClass(KnowledgeSyncUpsertRequest.class);
        verify(syncClient).save(sent.capture());
        assertEquals(4L, sent.getValue().serverId(), "path scope wins over the body");
        assertEquals(2L, sent.getValue().projectId(), "ownership fields are never touched by the bridge");
        assertEquals("fetch_page", sent.getValue().toolName());
        assertEquals(9L, result.id());
    }

    /** Detail and update forward the subscription id and payload without rewriting core errors. */
    @Test
    void detailAndUpdateForwardUnwrapped() {
        login();
        KnowledgeSyncUpsertRequest edit = new KnowledgeSyncUpsertRequest(
                2L, 4L, "kb-renamed", "fetch_page", Map.of(),
                null, 0L, 0L, Boolean.TRUE);
        when(syncClient.detail(9L)).thenReturn(row(9L, 4L));
        when(syncClient.update(9L, edit)).thenReturn(row(9L, 4L));

        assertEquals("kb-nightly", controller.detail(4L, 9L).getData().name());
        assertNull(controller.update(4L, 9L, edit).getData().lastError(),
                "bookkeeping columns ride on the core view untouched");
        verify(syncClient).update(9L, edit);
    }

    /** The three lifecycle legs delegate by subscription id and carry the void core answer through. */
    @Test
    void lifecycleLegsDelegateBySubscriptionId() {
        login();

        controller.remove(4L, 9L);
        controller.pause(4L, 9L);
        controller.resume(4L, 9L);

        verify(syncClient).delete(9L);
        verify(syncClient).pause(9L);
        verify(syncClient).resume(9L);
    }

    /** A run-now failure stays a typed round outcome (status/error), never a bridge-side exception. */
    @Test
    void runNowCarriesTypedRoundOutcome() {
        login();
        when(syncClient.runNow(9L)).thenReturn(
                new KnowledgeSyncRoundView("failed", "probe:network unreachable endpoint", 0, 0));

        KnowledgeSyncRoundView result = controller.runNow(4L, 9L).getData();

        assertEquals("failed", result.status());
        assertEquals("probe:network unreachable endpoint", result.error(),
                "the typed failure reason is rendered as-is, the bridge adds no 5xx");
        verify(syncClient).runNow(9L);
    }

    /** Non-ADMIN is rejected by the entity gate on all eight legs before any core interaction. */
    @Test
    void nonAdminRejectedBeforeCoreCallOnEveryLeg() {
        login();
        doThrow(new ServiceException("only system administrators can manage MCP servers",
                BizErrorCode.FORBIDDEN)).when(mcpGuard).assertCanManage(any(CurrentUser.class));

        KnowledgeSyncUpsertRequest body = new KnowledgeSyncUpsertRequest(
                2L, 4L, "n", "t", Map.of(), 60, 2L, 0L, null);
        assertThrows(ServiceException.class, () -> controller.list(4L, null, 1, 20));
        assertThrows(ServiceException.class, () -> controller.detail(4L, 9L));
        assertThrows(ServiceException.class, () -> controller.create(4L, body));
        assertThrows(ServiceException.class, () -> controller.update(4L, 9L, body));
        assertThrows(ServiceException.class, () -> controller.remove(4L, 9L));
        assertThrows(ServiceException.class, () -> controller.pause(4L, 9L));
        assertThrows(ServiceException.class, () -> controller.resume(4L, 9L));
        assertThrows(ServiceException.class, () -> controller.runNow(4L, 9L));

        verifyNoInteractions(syncClient);
    }

    /** Wiring: all eight legs carry the management key and none of the nested paths is public. */
    @Test
    void eightLegsWiredWithManageKeyAndNoExemption() throws Exception {
        assertEquals(KEY, permOf("list", long.class, Long.class, int.class, int.class), "list leg");
        assertEquals(KEY, permOf("detail", long.class, long.class), "detail leg");
        assertEquals(KEY, permOf("create", long.class, KnowledgeSyncUpsertRequest.class), "register leg");
        assertEquals(KEY, permOf("update", long.class, long.class, KnowledgeSyncUpsertRequest.class), "edit leg");
        assertEquals(KEY, permOf("remove", long.class, long.class), "delete leg");
        assertEquals(KEY, permOf("pause", long.class, long.class), "pause leg");
        assertEquals(KEY, permOf("resume", long.class, long.class), "resume leg");
        assertEquals(KEY, permOf("runNow", long.class, long.class), "run-now leg");

        String[][] wired = {
                {"GET", "/api/v1/mcp-servers/4/knowledge-syncs"},
                {"GET", "/api/v1/mcp-servers/4/knowledge-syncs/9"},
                {"POST", "/api/v1/mcp-servers/4/knowledge-syncs"},
                {"PUT", "/api/v1/mcp-servers/4/knowledge-syncs/9"},
                {"DELETE", "/api/v1/mcp-servers/4/knowledge-syncs/9"},
                {"POST", "/api/v1/mcp-servers/4/knowledge-syncs/9/pause"},
                {"POST", "/api/v1/mcp-servers/4/knowledge-syncs/9/resume"},
                {"POST", "/api/v1/mcp-servers/4/knowledge-syncs/9/run-now"},
        };
        for (String[] ep : wired) {
            assertFalse(PublicEndpointRegistry.isPublic(ep[0], ep[1]),
                    "keyed legs must not be registered as public: " + ep[0] + " " + ep[1]);
        }
    }

    /** Reads the permission annotation reflectively (a signature drift fails the test first). */
    private static String permOf(String methodName, Class<?>... params) throws Exception {
        Method m = McpKnowledgeSyncController.class.getMethod(methodName, params);
        RequirePermission ann = m.getAnnotation(RequirePermission.class);
        return ann == null ? null : ann.value();
    }
}
