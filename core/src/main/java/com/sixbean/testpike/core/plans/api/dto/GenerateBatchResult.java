package com.sixbean.testpike.core.plans.api.dto;

import java.util.List;


public record GenerateBatchResult(long planId, int batchCount, int taskCount, int skippedNoScript,
                                  int skipCount, List<SkipItem> skippedCases, String message,
                                  String envHealthWarning) {
}
