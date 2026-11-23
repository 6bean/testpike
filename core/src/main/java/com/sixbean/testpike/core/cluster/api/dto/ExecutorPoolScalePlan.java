package com.sixbean.testpike.core.cluster.api.dto;

/**
 * One autoscaler round's full answer for a single executor pool (T294c orchestration view).
 * <p>Bundles the raw demand line with the decision taken on it, so a consumer (fleet page
 * today, a scale driver later) sees the inputs and the verdict together and never has to
 * re-derive either side. {@code executorType} repeats the pool key for convenient flattening.
 */
public record ExecutorPoolScalePlan(
        String executorType,
        ExecutorPoolDemand demand,
        ExecutorScaleDecision decision) {
}
