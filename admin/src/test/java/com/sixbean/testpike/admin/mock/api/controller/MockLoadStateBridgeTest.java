package com.sixbean.testpike.admin.mock.api.controller;

import com.sixbean.testpike.admin.mock.api.dto.MockLoadStateView;
import com.sixbean.testpike.admin.mock.infrastructure.remote.client.MockServerLoadClient;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Load-state admin bridge contract test the request
 * inspector's health-bar door).
 * <p>Asserted surface: ①all ten self-check fields pass through the bridge
 * verbatim — stale/neverLoaded/drop-counters are the honest faces the
 * inspector page renders as-is, smoothing any of them into a prettier value
 * at the bridge would defeat the fail-closed design (AG ruling D-b);
 * ②null fields stay null (never-pinned plane reports no version and no
 * timestamp — the UI shows an honest empty, the bridge must not invent one);
 * ③a downstream ServiceException (plane unreachable, human-worded 503 by the
 * client) propagates untouched — no rewrite, no swallowed code;
 * ④the lane carries the RUN-family traffic key perm:mock:traffic, same family
 * as the hits door (observing the plane is one act, gated once).
 * <p>MockServerLoadClient stand-in = L2 bridge-contract isolation (precedent:
 * MockRuleBridgeTest); the real admin→mock-server chain is verified at L3.
 */
class MockLoadStateBridgeTest {

    private final MockServerLoadClient client = mock(MockServerLoadClient.class);
    private final MockLoadStateController controller = new MockLoadStateController(client);

    @Test
    void loadState_passesAllTenFieldsThrough_noSmoothing() {
        LocalDateTime now = LocalDateTime.now();
        when(client.loadState()).thenReturn(new MockLoadStateView(
                true, "pay-sbx", "v7", now, true, 11, "core snapshot poll failed: 500",
                42, true, 3L));

        MockLoadStateView head = controller.loadState(1L).getData();
        assertTrue(head.everLoaded());
        assertEquals("pay-sbx", head.pinnedSpaceKey());
        assertEquals("v7", head.pinnedVersion());
        assertEquals(now, head.lastSuccessAt());
        assertTrue(head.stale(), "stale flag must survive the bridge — the UI names it in plain words");
        assertEquals(11, head.consecutiveFailures());
        assertEquals("core snapshot poll failed: 500", head.lastError());
        assertEquals(42, head.hitBuffered());
        assertTrue(head.hitDegraded(), "ledger degradation must not be softened at the bridge");
        assertEquals(3L, head.hitOverflowDrops(), "overflow drops are exposed, never hidden");
    }

    @Test
    void neverPinnedPlane_keepsNullHonestEmpties() {
        when(client.loadState()).thenReturn(new MockLoadStateView(
                false, null, null, null, true, 0, null, 0, false, 0L));

        MockLoadStateView head = controller.loadState(1L).getData();
        assertTrue(!head.everLoaded());
        assertNull(head.pinnedSpaceKey(), "no pin = null, the bridge must not substitute an empty string");
        assertNull(head.pinnedVersion());
        assertNull(head.lastSuccessAt());
        assertNull(head.lastError());
    }

    @Test
    void planeUnreachableError_propagatesUntouched_noRewrite() {
        when(client.loadState()).thenThrow(new ServiceException(
                "Mock 流量面未响应自检口，可能尚未启动（管理口 18085 不可达）", 50301));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.loadState(1L));
        assertTrue(ex.getMessage().contains("18085"), "human message passes through untouched");
        assertEquals(50301, ex.getCode().intValue());
    }

    @Test
    void loadStateLane_carriesTrafficKeySameFamilyAsHits() throws NoSuchMethodException {
        Method m = MockLoadStateController.class.getMethod("loadState", long.class);
        RequirePermission ann = m.getAnnotation(RequirePermission.class);
        assertEquals("perm:mock:traffic", ann == null ? null : ann.value(),
                "health bar observes the serving plane — RUN family, same door family as hits");
    }
}
