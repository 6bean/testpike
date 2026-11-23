package com.sixbean.testpike.core.judge.domain.component;

import java.util.List;


public record SamplingPlan(CalibrationStage stage, List<Long> recordIds,
                           int boundaryPicked, int estimatedCount) {

    public SamplingPlan {
        recordIds = List.copyOf(recordIds);
    }
}
