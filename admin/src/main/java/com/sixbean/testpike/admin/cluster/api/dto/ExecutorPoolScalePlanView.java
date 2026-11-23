package com.sixbean.testpike.admin.cluster.api.dto;

/**
 * Mirror of one autoscaler round's full answer for a single executor pool (same shape
 * as core ExecutorPoolScalePlan: pool key + demand line + verdict). The fleet overview
 * card row consumes demand and decision together so the operator sees why the line is
 * what it is without a second call.
 */
public record ExecutorPoolScalePlanView(
        String executorType,
        ExecutorPoolDemandView demand,
        ExecutorPoolScaleDecisionView decision) {
}
