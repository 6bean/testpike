package com.sixbean.testpike.core.cluster.domain.service.impl;

import com.sixbean.testpike.core.cluster.api.dto.ExecutorPoolDemand;
import com.sixbean.testpike.core.cluster.api.dto.ExecutorPoolScalePlan;
import com.sixbean.testpike.core.cluster.api.dto.ExecutorPoolScaleSettings;
import com.sixbean.testpike.core.cluster.api.dto.ExecutorScaleDecision;
import com.sixbean.testpike.core.cluster.domain.component.ExecutorPoolScaleRules;
import com.sixbean.testpike.core.cluster.domain.component.ExecutorPoolScaleSettingsReader;
import com.sixbean.testpike.core.cluster.domain.service.ExecutorPoolDemandService;
import com.sixbean.testpike.core.cluster.domain.service.ExecutorPoolScaleOrchestrationService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Straight composition of demand snapshot + guardrail settings into per-pool decisions.
 *
 * <p>Ordering within one round matters and is pinned here: guardrails are read once and
 * shared by every pool, so one round is internally consistent even if an operator retunes
 * a key mid-loop; each pool's verdict comes exclusively from
 * {@link ExecutorPoolScaleRules#decide} (single decision source — this class adds no
 * thresholds of its own, R40). The result is a plain read-only snapshot list: applying it
 * to real replicas is the scale driver's job on its own channel (KEDA or the degraded
 * path), decided with the environment window in the closing verification package.
 */
@Service
public class ExecutorPoolScaleOrchestrationServiceImpl implements ExecutorPoolScaleOrchestrationService {

    private final ExecutorPoolDemandService demandService;
    private final ExecutorPoolScaleSettingsReader settingsReader;

    public ExecutorPoolScaleOrchestrationServiceImpl(ExecutorPoolDemandService demandService,
                                                     ExecutorPoolScaleSettingsReader settingsReader) {
        this.demandService = demandService;
        this.settingsReader = settingsReader;
    }

    @Override
    public List<ExecutorPoolScalePlan> evaluateAll(double budgetUtilization) {
        ExecutorPoolScaleSettings settings = settingsReader.read();
        List<ExecutorPoolDemand> demands = demandService.snapshotPools();
        return demands.stream()
                .map(demand -> new ExecutorPoolScalePlan(
                        demand.executorType(),
                        demand,
                        ExecutorPoolScaleRules.decide(demand, settings, budgetUtilization)))
                .toList();
    }
}
