package com.sixbean.testpike.core.cluster.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.cluster.api.dto.ExecutorPoolScaleEvent;
import com.sixbean.testpike.core.cluster.api.dto.ExecutorPoolScalePlan;
import com.sixbean.testpike.core.cluster.api.dto.ExecutorPoolWatermarkPoint;
import com.sixbean.testpike.core.cluster.domain.service.ExecutorPoolScaleEventLedger;
import com.sixbean.testpike.core.cluster.domain.service.ExecutorPoolScaleOrchestrationService;
import com.sixbean.testpike.core.cluster.domain.service.ExecutorPoolWatermarkLedger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Autoscaler plan read port (T294d data plane; consumed by the admin fleet bridge).
 * <p>Thin forwarding only: one evaluation round through ExecutorPoolScaleOrchestrationService
 * (guardrails read once, per-pool verdicts from the single rules source) and the plan list
 * wrapped into the standard envelope. No clamping, no re-decision, no replica writes here —
 * applying the plan is the scale driver's job on its own channel (decided in the closing
 * environment window).
 * <p>The budget utilization line is an input the caller carries (the cost domain owns the
 * money ledger). Absent means 0.0 = "no budget evidence", which the rules source treats as
 * budget guard inactive; the port never guesses a water level of its own.
 */
@RestController
@RequestMapping("/internal/cluster/pools")
public class ExecutorPoolScaleInternalController {

    /** Default timeline window when the caller asks for no explicit size. */
    static final int DEFAULT_EVENT_WINDOW = 50;

    /** Default curve window when the caller asks for no explicit size. */
    static final int DEFAULT_WATERMARK_WINDOW = 200;

    private final ExecutorPoolScaleOrchestrationService orchestrationService;
    private final ExecutorPoolScaleEventLedger scaleEventLedger;
    private final ExecutorPoolWatermarkLedger watermarkLedger;

    public ExecutorPoolScaleInternalController(ExecutorPoolScaleOrchestrationService orchestrationService,
            ExecutorPoolScaleEventLedger scaleEventLedger,
            ExecutorPoolWatermarkLedger watermarkLedger) {
        this.orchestrationService = orchestrationService;
        this.scaleEventLedger = scaleEventLedger;
        this.watermarkLedger = watermarkLedger;
    }

    /** Per-pool demand + verdict snapshot for the whole fleet, in one consistent round. */
    @GetMapping("/scale-plan")
    public Response<List<ExecutorPoolScalePlan>> scalePlan(
            @RequestParam(name = "budgetUtilization", required = false) Double budgetUtilization) {
        double utilization = budgetUtilization == null ? 0.0 : budgetUtilization;
        return Response.builderSuccess(orchestrationService.evaluateAll(utilization));
    }

    /**
     * Newest-first scale-event window for the fleet timeline. A missing pool filter
     * spans the fleet, a missing limit asks the default window; the ledger clamps
     * both sides. Rows pass through verbatim — the reason sentence was written by
     * the apply channel and is never rewritten here (W6 posture for read ports).
     */
    @GetMapping("/scale-events")
    public Response<List<ExecutorPoolScaleEvent>> scaleEvents(
            @RequestParam(name = "poolKey", required = false) String poolKey,
            @RequestParam(name = "limit", required = false) Integer limit) {
        int size = limit == null ? DEFAULT_EVENT_WINDOW : limit;
        return Response.builderSuccess(scaleEventLedger.recentEvents(poolKey, size));
    }

    /**
     * Newest-first water-level samples for the fleet curve. A missing executorType
     * spans every pool, a missing limit asks the default window; the ledger owns the
     * clamp and the retention purge — this port decides nothing (R40).
     */
    @GetMapping("/watermarks")
    public Response<List<ExecutorPoolWatermarkPoint>> watermarks(
            @RequestParam(name = "executorType", required = false) String executorType,
            @RequestParam(name = "limit", required = false) Integer limit) {
        int size = limit == null ? DEFAULT_WATERMARK_WINDOW : limit;
        return Response.builderSuccess(watermarkLedger.recentSeries(executorType, size));
    }
}
