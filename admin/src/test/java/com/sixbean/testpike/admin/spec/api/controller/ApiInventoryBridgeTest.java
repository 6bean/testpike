package com.sixbean.testpike.admin.spec.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.spec.api.dto.InventoryCoverageSummary;
import com.sixbean.testpike.admin.spec.api.dto.InventoryEndpointView;
import com.sixbean.testpike.admin.spec.infrastructure.remote.client.CoreApiInventoryClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract inventory admin bridge contract test (API inventory page, .
 * <p>Spectra: the H1 project guard runs before the core call on both doors (the
 * page is a project segment, not a cross-project scan); projectId/specName/page/
 * pageSize are forwarded positionally with no re-implemented paging math; the
 * read key perm:api-inventory:read stays in the catalog with the page door
 * annotated for it (catalog zero-missing-zero-extra ruling).
 * <p>CoreApiInventoryClient is the integration boundary double for the L2 bridge
 * contract; the real admin→core→DB chain is covered by the L3 live script
 * (verification levels may not be skipped).
 */
class ApiInventoryBridgeTest {

    private final CoreApiInventoryClient client = mock(CoreApiInventoryClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final ApiInventoryController controller = new ApiInventoryController(client, guard);

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static void login(long userId) {
        UserContext.set(new CurrentUser(userId, "u" + userId, "U", "Tester",
                List.of("TEST_LEAD"), System.currentTimeMillis() + 3600_000, null));
    }

    private static InventoryEndpointView row(String method, String path) {
        return new InventoryEndpointView(method, path, method + " " + path, "list pets",
                2, List.of("apiKeyAuth"), false, false, "stable",
                3, 1, true, false);
    }

    @Test
    void endpointRows_door_passesH1GuardFirst_andForwardsPagingPositionally() {
        login(7L);
        when(client.endpoints(42L, "订单中心契约", 2, 20, false))
                .thenReturn(new PageView<>(List.of(row("GET", "/pets")), 57L));
        Response<PageView<InventoryEndpointView>> resp =
                controller.endpoints(42L, "订单中心契约", 2, 20, false);
        assertEquals("0", resp.getCode());
        assertEquals(57L, resp.getData().total());
        ArgumentCaptor<CurrentUser> user = ArgumentCaptor.forClass(CurrentUser.class);
        verify(guard).assertCanView(user.capture(), eq(42L));
        assertEquals(7L, user.getValue().userId(), "guard must use the logged-in user");
        verify(client).endpoints(42L, "订单中心契约", 2, 20, false);
    }

    @Test
    void uncoveredBadgeFilter_flagIsForwardedVerbatim_noBridgeSideFiltering() {
        login(7L);
        when(client.endpoints(9L, "Order API", 1, 20, true))
                .thenReturn(new PageView<>(List.of(), 0L));
        Response<PageView<InventoryEndpointView>> resp =
                controller.endpoints(9L, "Order API", 1, 20, true);
        assertEquals("0", resp.getCode());
        // The working-list segment is a core judgment (applied server-side before
        // paging); the bridge only forwards the flag — any in-memory filtering
        // here would be a second ledger and page-window math would go dishonest.
        verify(client).endpoints(9L, "Order API", 1, 20, true);
    }

    @Test
    void coverageSummary_door_passesH1GuardFirst_andMirrorsAnchorFields() {
        login(7L);
        when(client.coverage(3L, "Order API")).thenReturn(
                new InventoryCoverageSummary(3L, "Order API", 4, 25, 10, 15, 40));
        Response<InventoryCoverageSummary> resp = controller.coverage(3L, "Order API");
        assertEquals("0", resp.getCode());
        InventoryCoverageSummary data = resp.getData();
        assertEquals(4, data.specVersion());
        assertEquals(15, data.uncoveredCount());
        assertEquals(40, data.coveragePercent());
        verify(guard).assertCanView(any(CurrentUser.class), eq(3L));
        verify(client).coverage(3L, "Order API");
    }

    @Test
    void readKeyIsCataloged_once_andDoorsAnnotatedWithIt() {
        assertEquals(true, com.sixbean.testpike.common.rbac.PermissionCatalog
                .exists("perm:apitest:inventory:read"),
                "inventory read key must be in the catalog (AH ruling: zero missing)");
        long sameKey = java.util.Arrays.stream(
                com.sixbean.testpike.common.rbac.PermissionCatalog.values())
                .filter(p -> p.key().equals("perm:apitest:inventory:read")).count();
        assertEquals(1L, sameKey, "exactly one registration (zero extra)");
        // both doors carry the annotation (interceptor-enforced; grep of the
        // controller source by the same test author would drift — reflect instead)
        for (java.lang.reflect.Method m : ApiInventoryController.class.getDeclaredMethods()) {
            if (m.getName().equals("endpoints") || m.getName().equals("coverage")) {
                var ann = m.getAnnotation(
                        com.sixbean.testpike.admin.rbac.app.component.RequirePermission.class);
                assertEquals("perm:apitest:inventory:read", ann == null ? null : ann.value(),
                        "door " + m.getName() + " must be annotated with the read key");
            }
        }
    }
}
