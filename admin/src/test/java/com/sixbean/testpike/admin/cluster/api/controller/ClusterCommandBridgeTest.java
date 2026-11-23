package com.sixbean.testpike.admin.cluster.api.controller;

import com.sixbean.testpike.admin.cluster.api.dto.ClusterCommandView;
import com.sixbean.testpike.admin.cluster.infrastructure.remote.client.CoreClusterCommandClient;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contracts of the admin cluster-command read bridge (ExecutorPoolFleetBridgeTest precedent).
 * <p>Asserted surface: ①core's command rows project field-by-field onto the mirrored view
 * with the same names — the fleet command ledger must see dispatch state, progress and
 * receipt together; admin re-derives no status verdict (R40, red line 6); ②the receipt
 * detail and progress message cross the bridge unchanged (no truncation, no second
 * wording source — the honest absence of a receipt must stay visibly absent);
 * ③a core ServiceException surfaces to the caller untouched — the bridge never swallows
 * or restates it; ④the optional targetExecutorId filter is forwarded verbatim, absent
 * spans the whole fleet (filtering is core's job, not a second predicate here).
 * <p>CoreClusterCommandClient stub = L2 bridge-contract isolation (health-bridge
 * precedent); the real admin→core chain is covered by the L3 live window of the
 * frontend closing package.
 */
class ClusterCommandBridgeTest {

    private final CoreClusterCommandClient client = mock(CoreClusterCommandClient.class);
    private final ClusterCommandController controller = new ClusterCommandController(client);

    @Test
    void command_rows_project_field_by_field_and_receipt_crosses_unchanged() {
        LocalDateTime created = LocalDateTime.of(2026, 11, 22, 8, 0, 0);
        ClusterCommandView row = new ClusterCommandView(
                41L, "NODE_SELF_CHECK", "executor", 7L,
                Map.of("timeoutSeconds", 60), 3, 7L,
                Map.of("step", "verify", "message", "自检通过", "at", 1_763_000_000L),
                "自检通过：设备清单与驱动版本一致",
                LocalDateTime.of(2026, 11, 22, 8, 5, 0), created);
        when(client.list(7L)).thenReturn(List.of(row));

        List<ClusterCommandView> got = controller.list(7L).getData();

        assertEquals(1, got.size());
        ClusterCommandView got0 = got.get(0);
        assertEquals(41L, got0.id());
        assertEquals("NODE_SELF_CHECK", got0.commandType());
        assertEquals("executor", got0.targetType());
        assertEquals(7L, got0.targetId());
        assertEquals(60, got0.payload().get("timeoutSeconds"));
        assertEquals(3, got0.status());
        assertEquals(7L, got0.claimedBy());
        assertEquals("verify", got0.lastProgress().get("step"));
        assertEquals("自检通过：设备清单与驱动版本一致", got0.resultDetail());
        assertEquals(LocalDateTime.of(2026, 11, 22, 8, 5, 0), got0.expiresAt());
        assertEquals(created, got0.createdAt());
        verify(client).list(7L);
    }

    @Test
    void unclaimed_pending_row_crosses_with_honest_absences() {
        ClusterCommandView row = new ClusterCommandView(
                42L, "NODE_RESTART", "executor", 9L,
                Map.of(), 0, null, null, null, null, null);
        when(client.list(null)).thenReturn(List.of(row));

        ClusterCommandView got0 = controller.list(null).getData().get(0);

        assertEquals(0, got0.status());
        assertNull(got0.claimedBy());
        assertNull(got0.lastProgress());
        assertNull(got0.resultDetail());
        verify(client).list(null);
    }

    @Test
    void core_service_exception_surfaces_untouched() {
        when(client.list(3L)).thenThrow(new ServiceException("目标执行器不存在或已下线，请到资源池视图确认设备状态"));
        ServiceException thrown = assertThrows(ServiceException.class, () -> controller.list(3L));
        assertEquals("目标执行器不存在或已下线，请到资源池视图确认设备状态", thrown.getMessage());
    }
}
