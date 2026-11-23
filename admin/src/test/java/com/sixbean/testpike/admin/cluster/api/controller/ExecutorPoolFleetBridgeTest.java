package com.sixbean.testpike.admin.cluster.api.controller;

import com.sixbean.testpike.admin.cluster.api.dto.ExecutorPoolDemandView;
import com.sixbean.testpike.admin.cluster.api.dto.ExecutorPoolScaleDecisionView;
import com.sixbean.testpike.admin.cluster.api.dto.ExecutorPoolScaleEventView;
import com.sixbean.testpike.admin.cluster.api.dto.ExecutorPoolScalePlanView;
import com.sixbean.testpike.admin.cluster.api.dto.ExecutorPoolWatermarkView;
import com.sixbean.testpike.admin.cluster.infrastructure.remote.client.CoreExecutorPoolScaleClient;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contracts of the admin fleet-plan bridge (ClusterComponentBridgeTest precedent).
 * <p>Asserted surface: ①core's nested plan projects field-by-field onto the mirrored
 * views with the same names (demand line and decision both complete — the fleet page
 * must see the inputs and the verdict together; admin re-derives nothing, R40);
 * ②the user-facing reason sentence crosses the bridge unchanged (no truncation, no
 * second wording source); ③a core ServiceException surfaces to the caller untouched —
 * the bridge never swallows or restates it; ④the utilization parameter is forwarded
 * to the client as the caller gave it.
 * <p>CoreExecutorPoolScaleClient stub = L2 bridge-contract isolation (health-bridge
 * precedent); the real admin→core chain is covered by the L3 live window of the
 * frontend closing package.
 */
class ExecutorPoolFleetBridgeTest {

    private final CoreExecutorPoolScaleClient client = mock(CoreExecutorPoolScaleClient.class);
    private final ExecutorPoolFleetController controller = new ExecutorPoolFleetController(client);

    @Test
    void nested_plan_projects_field_by_field_and_reason_crosses_unchanged() {
        ExecutorPoolDemandView demand =
                new ExecutorPoolDemandView("mobile", 9, 1, 1, 128.0, 0L);
        ExecutorPoolScaleDecisionView decision = new ExecutorPoolScaleDecisionView(
                "SCALE_OUT", 3, 2, false,
                "mobile 池待派发 9 条，等待偏久，建议扩到 3 台");
        when(client.scalePlan(0.5)).thenReturn(
                List.of(new ExecutorPoolScalePlanView("mobile", demand, decision)));

        List<ExecutorPoolScalePlanView> got = controller.scalePlan(0.5).getData();

        assertEquals(1, got.size());
        ExecutorPoolScalePlanView plan = got.get(0);
        assertEquals("mobile", plan.executorType());
        assertEquals(9, plan.demand().pendingDispatchDepth());
        assertEquals(1, plan.demand().currentReplicas());
        assertEquals(128.0, plan.demand().p95WaitSeconds(), 0.001);
        assertEquals(0L, plan.demand().lastScaleEpochSeconds());
        assertEquals(3, plan.decision().desiredReplicas());
        assertEquals(2, plan.decision().targetReplicas());
        assertEquals("SCALE_OUT", plan.decision().action());
        assertEquals(Boolean.FALSE, plan.decision().drainFirst());
        assertEquals("mobile 池待派发 9 条，等待偏久，建议扩到 3 台", plan.decision().reason());
        verify(client).scalePlan(0.5);
    }

    @Test
    void core_service_exception_surfaces_untouched() {
        when(client.scalePlan(0.0)).thenThrow(new ServiceException("系统设置中的弹性开关暂时不可读，请到系统设置页面检查"));
        ServiceException thrown = assertThrows(ServiceException.class,
                () -> controller.scalePlan(0.0));
        assertEquals("系统设置中的弹性开关暂时不可读，请到系统设置页面检查", thrown.getMessage());
    }

    @Test
    void scale_events_project_field_by_field_and_reason_crosses_unchanged() {
        ExecutorPoolScaleEventView row = new ExecutorPoolScaleEventView(
                "mobile", "SCALE_OUT", 3, 2,
                "mobile 池待派发 9 条，等待偏久，建议扩到 3 台", 1_760_000_000L);
        when(client.scaleEvents("mobile", 20)).thenReturn(List.of(row));

        List<ExecutorPoolScaleEventView> got = controller.scaleEvents("mobile", 20).getData();

        assertEquals(1, got.size());
        ExecutorPoolScaleEventView event = got.get(0);
        assertEquals("mobile", event.poolKey());
        assertEquals("SCALE_OUT", event.action());
        assertEquals(3, event.desiredReplicas());
        assertEquals(2, event.currentReplicas());
        assertEquals("mobile 池待派发 9 条，等待偏久，建议扩到 3 台", event.reason());
        assertEquals(1_760_000_000L, event.createdEpochSeconds());
        verify(client).scaleEvents("mobile", 20);
    }

    @Test
    void scale_events_forward_absent_filters_verbatim() {
        // Absent poolKey spans the fleet and absent limit takes the core-side default
        // window; admin guesses neither — the clamp lives in the ledger source alone.
        when(client.scaleEvents(null, null)).thenReturn(List.of());

        assertEquals(List.of(), controller.scaleEvents(null, null).getData());
        verify(client).scaleEvents(null, null);
    }

    @Test
    void scale_events_core_service_exception_surfaces_untouched() {
        when(client.scaleEvents("web", 5)).thenThrow(new ServiceException("伸缩事件账暂时不可读，请稍后在机群页重试"));
        ServiceException thrown = assertThrows(ServiceException.class,
                () -> controller.scaleEvents("web", 5));
        assertEquals("伸缩事件账暂时不可读，请稍后在机群页重试", thrown.getMessage());
    }

    @Test
    void watermark_points_project_field_by_field() {
        ExecutorPoolWatermarkView row = new ExecutorPoolWatermarkView(
                "mobile", 9, 2, 1, 128.0, 1_760_000_000L);
        when(client.watermarks("mobile", 20)).thenReturn(List.of(row));

        List<ExecutorPoolWatermarkView> got = controller.watermarks("mobile", 20).getData();

        assertEquals(1, got.size());
        ExecutorPoolWatermarkView point = got.get(0);
        assertEquals("mobile", point.executorType());
        assertEquals(9, point.pendingDispatchDepth());
        assertEquals(2, point.currentReplicas());
        assertEquals(1, point.activeExecutors());
        assertEquals(128.0, point.p95WaitSeconds(), 0.001);
        assertEquals(1_760_000_000L, point.sampledEpochSeconds());
        verify(client).watermarks("mobile", 20);
    }

    @Test
    void watermarks_forward_absent_filters_verbatim() {
        // Absent executorType spans the fleet and absent limit takes the core-side default
        // window; admin guesses neither — the clamp lives in the ledger source alone.
        when(client.watermarks(null, null)).thenReturn(List.of());

        assertEquals(List.of(), controller.watermarks(null, null).getData());
        verify(client).watermarks(null, null);
    }

    @Test
    void watermarks_core_service_exception_surfaces_untouched() {
        when(client.watermarks("web", 5)).thenThrow(new ServiceException("水位历史暂时不可读，请稍后在机群页重试"));
        ServiceException thrown = assertThrows(ServiceException.class,
                () -> controller.watermarks("web", 5));
        assertEquals("水位历史暂时不可读，请稍后在机群页重试", thrown.getMessage());
    }
}
