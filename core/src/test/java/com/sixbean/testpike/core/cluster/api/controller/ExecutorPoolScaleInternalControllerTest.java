package com.sixbean.testpike.core.cluster.api.controller;

import com.sixbean.testpike.core.cluster.api.dto.ExecutorPoolDemand;
import com.sixbean.testpike.core.cluster.api.dto.ExecutorPoolScalePlan;
import com.sixbean.testpike.core.cluster.api.dto.ExecutorPoolScaleEvent;
import com.sixbean.testpike.core.cluster.api.dto.ExecutorScaleDecision;
import com.sixbean.testpike.core.cluster.api.dto.ExecutorPoolWatermarkPoint;
import com.sixbean.testpike.core.cluster.domain.service.ExecutorPoolScaleEventLedger;
import com.sixbean.testpike.core.cluster.domain.service.ExecutorPoolScaleOrchestrationService;
import com.sixbean.testpike.core.cluster.domain.service.ExecutorPoolWatermarkLedger;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contracts of the autoscaler plan read port (T294d data plane, same thin-controller
 * posture as ClusterComponentInternalControllerTest): the controller only wraps the
 * orchestration round's answer — no re-decision, no re-clamping here.
 * <p>Asserted surface: ①plan list passes through verbatim (demand line and decision
 * fields intact, the user-facing reason sentence must never be truncated or rewritten
 * at the controller layer — the single decision source is ExecutorPoolScaleRules);
 * ②a missing budget-utilization request parameter normalizes to 0.0 before entering the
 * round (0 = no budget protection evidence, honest default instead of guessing green);
 * ③an empty fleet yields an empty list, never null.
 */
class ExecutorPoolScaleInternalControllerTest {

    private final ExecutorPoolScaleOrchestrationService orchestration =
            mock(ExecutorPoolScaleOrchestrationService.class);
    private final ExecutorPoolScaleEventLedger ledger =
            mock(ExecutorPoolScaleEventLedger.class);
    private final ExecutorPoolWatermarkLedger watermarkLedger =
            mock(ExecutorPoolWatermarkLedger.class);
    private final ExecutorPoolScaleInternalController controller =
            new ExecutorPoolScaleInternalController(orchestration, ledger, watermarkLedger);

    @Test
    void plan_snapshot_passes_through_verbatim_with_demand_and_decision_intact() {
        ExecutorPoolDemand demand = new ExecutorPoolDemand("web", 7, 2, 2, 42.5, 1_700_000_000L);
        ExecutorScaleDecision decision = new ExecutorScaleDecision(
                4, 3, ExecutorScaleDecision.ACTION_SCALE_OUT, false,
                "web 池待派发 7 条超出当前容量，建议扩到 4 台");
        when(orchestration.evaluateAll(0.35)).thenReturn(
                List.of(new ExecutorPoolScalePlan("web", demand, decision)));

        List<ExecutorPoolScalePlan> got = controller.scalePlan(0.35).getData();

        assertEquals(1, got.size());
        ExecutorPoolScalePlan plan = got.get(0);
        assertEquals("web", plan.executorType());
        assertEquals(7, plan.demand().pendingDispatchDepth());
        assertEquals(2, plan.demand().currentReplicas());
        assertEquals(2, plan.demand().activeExecutors());
        assertEquals(42.5, plan.demand().p95WaitSeconds(), 0.001);
        assertEquals(1_700_000_000L, plan.demand().lastScaleEpochSeconds());
        assertEquals(4, plan.decision().desiredReplicas());
        assertEquals(3, plan.decision().targetReplicas());
        assertEquals(ExecutorScaleDecision.ACTION_SCALE_OUT, plan.decision().action());
        assertEquals("web 池待派发 7 条超出当前容量，建议扩到 4 台", plan.decision().reason());
        verify(orchestration).evaluateAll(0.35);
    }

    @Test
    void missing_budget_utilization_normalizes_to_zero_before_the_round() {
        when(orchestration.evaluateAll(anyDouble())).thenReturn(List.of());

        controller.scalePlan(null);

        verify(orchestration).evaluateAll(0.0);
    }

    @Test
    void empty_fleet_answers_empty_list_not_null() {
        when(orchestration.evaluateAll(0.0)).thenReturn(List.of());
        assertTrue(controller.scalePlan(null).getData().isEmpty());
    }

    @Test
    void scale_event_window_passes_through_verbatim_and_keeps_order() {
        ExecutorPoolScaleEvent head = new ExecutorPoolScaleEvent(
                "web", "scale_up", 4, 3, "web 池待派发 7 条超出当前容量，建议扩到 4 台", 1_700_000_900L);
        ExecutorPoolScaleEvent tail = new ExecutorPoolScaleEvent(
                "web", "drain", 2, 3, "web 池积压回落，先排水再缩到 2 台", 1_700_000_300L);
        when(ledger.recentEvents("web", 20)).thenReturn(List.of(head, tail));

        List<ExecutorPoolScaleEvent> got = controller.scaleEvents("web", 20).getData();

        assertEquals(2, got.size());
        assertEquals(head, got.get(0));
        assertEquals(tail, got.get(1));
        verify(ledger).recentEvents("web", 20);
    }

    @Test
    void missing_limit_normalizes_to_the_default_window_and_pool_filter_passes_through() {
        when(ledger.recentEvents(isNull(), eq(50))).thenReturn(List.of());

        List<ExecutorPoolScaleEvent> got = controller.scaleEvents(null, null).getData();

        assertTrue(got.isEmpty(), "an un-scaled fleet reads an empty timeline window");
        verify(ledger).recentEvents(null, 50);
    }

    @Test
    void watermark_series_passes_through_verbatim_and_keeps_order() {
        ExecutorPoolWatermarkPoint head = new ExecutorPoolWatermarkPoint(
                "web", 9, 3, 2, 90.0, 1_700_000_900L);
        ExecutorPoolWatermarkPoint tail = new ExecutorPoolWatermarkPoint(
                "web", 2, 3, 2, 12.5, 1_700_000_300L);
        when(watermarkLedger.recentSeries("web", 120)).thenReturn(List.of(head, tail));

        List<ExecutorPoolWatermarkPoint> got = controller.watermarks("web", 120).getData();

        assertEquals(2, got.size());
        assertEquals(head, got.get(0));
        assertEquals(90.0, got.get(0).p95WaitSeconds(), 0.001);
        verify(watermarkLedger).recentSeries("web", 120);
    }

    @Test
    void missing_watermark_limit_normalizes_to_the_default_window_and_type_passes_through() {
        when(watermarkLedger.recentSeries(isNull(), eq(200))).thenReturn(List.of());

        List<ExecutorPoolWatermarkPoint> got = controller.watermarks(null, null).getData();

        assertTrue(got.isEmpty(), "an un-sampled fleet honestly answers an empty curve window");
        verify(watermarkLedger).recentSeries(null, 200);
    }
}
