package com.sixbean.testpike.admin.cluster.infrastructure.remote.client;

import com.sixbean.testpike.admin.cluster.api.dto.ExecutorPoolScaleEventView;
import com.sixbean.testpike.admin.cluster.api.dto.ExecutorPoolScalePlanView;
import com.sixbean.testpike.admin.cluster.api.dto.ExecutorPoolWatermarkView;

import java.util.List;

/**
 * Read port from the admin management plane to the core autoscaler plan snapshot
 * (CoreClusterComponentClient precedent, same module posture).
 */
public interface CoreExecutorPoolScaleClient {

    /**
     * One consistent evaluation round over every executor pool.
     *
     * @param budgetUtilization cost water level supplied by the caller; 0.0 means no
     *                          budget evidence and leaves the budget guard inactive
     */
    List<ExecutorPoolScalePlanView> scalePlan(double budgetUtilization);

    /**
     * Newest-first scale-event window for the fleet timeline.
     *
     * @param poolKey pool filter; null spans the whole fleet
     * @param limit   window size; null asks the core-side default window
     */
    List<ExecutorPoolScaleEventView> scaleEvents(String poolKey, Integer limit);

    /**
     * Newest-first pool-watermark series for the fleet curve band.
     *
     * @param executorType type filter; null spans every pool type
     * @param limit        window size; null asks the core-side default window
     */
    List<ExecutorPoolWatermarkView> watermarks(String executorType, Integer limit);
}
