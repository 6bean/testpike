package com.sixbean.testpike.core.cluster.api.dto;

/**
 * Autoscaler output for one pool: what the fleet should look like and why (T294c).
 * <p>{@code drainFirst} is the contract that a scale-in always drains the instance before it is
 * removed, so a running task is never killed mid-flight. {@code reason} is the user-facing line
 * shown on the fleet page — plain operational wording only, naming the pool and the cause.
 */
public record ExecutorScaleDecision(
        int desiredReplicas,
        int targetReplicas,
        String action,
        boolean drainFirst,
        String reason) {

    /** Queue depth exceeds capacity: grow toward the desired line. */
    public static final String ACTION_SCALE_OUT = "SCALE_OUT";
    /** Capacity exceeds demand: shrink gradually with drain first. */
    public static final String ACTION_SCALE_IN = "SCALE_IN";
    /** Nothing to change under the current guardrails. */
    public static final String ACTION_HELD = "HELD";
    /** Inside the cooldown window after the last scale event. */
    public static final String ACTION_COOLDOWN_HELD = "COOLDOWN_HELD";
    /** Elasticity manually paused from the fleet page. */
    public static final String ACTION_PAUSED = "PAUSED";
    /** Budget guard active: growth stopped, freeing capacity still allowed. */
    public static final String ACTION_BUDGET_PAUSED = "BUDGET_PAUSED";
}
