package com.sixbean.testpike.core.cluster.domain.service;

import com.sixbean.testpike.core.cluster.api.dto.ExecutorPoolScalePlan;

import java.util.List;

/**
 * Autoscaler orchestration channel for executor pools (T294c, third wiring segment).
 * <p>Composes the three delivered pieces once per round — hot-tunable guardrails from
 * {@code ExecutorPoolScaleSettingsReader}, the live demand spectrum from
 * {@code ExecutorPoolDemandService}, and the pure verdicts from
 * {@code ExecutorPoolScaleRules} — into one decision snapshot per online pool.
 *
 * <p>This is the read-only application face: it publishes what SHOULD happen and why,
 * while the channel that actually changes replicas (KEDA when present, the degraded
 * fallback when not) is deliberately not wired here — no fleet mutation happens through
 * this service, so the decision line stays reviewable from the fleet page first.
 */
public interface ExecutorPoolScaleOrchestrationService {

    /**
     * Decide every online pool under the current guardrails.
     *
     * @param budgetUtilization platform budget waterline in the 0..1 range (1 = spent
     *                          everything); supplied by the caller because the cost
     *                          domain owns the money math — values at or above
     *                          {@code cluster.budget-max-utilization} stop growth, while
     *                          scale-in stays allowed. Negative input behaves like zero.
     * @return one {@link ExecutorPoolScalePlan} per online pool, never null, empty when
     *         no executor is online
     */
    List<ExecutorPoolScalePlan> evaluateAll(double budgetUtilization);
}
