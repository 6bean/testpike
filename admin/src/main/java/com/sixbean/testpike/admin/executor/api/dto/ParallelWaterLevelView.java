package com.sixbean.testpike.admin.executor.api.dto;

import java.util.List;


public record ParallelWaterLevelView(long globalRunning, int globalMax, int perProjectMax,
                                     List<ProjectRunningView> projects) {
}
