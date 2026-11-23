package com.sixbean.testpike.admin.plans.api.dto;

import java.util.List;


public record GenerateBatchResultView(long planId, int batchCount, int taskCount, int skippedNoScript,
                                      int skipCount, List<SkipItemView> skippedCases, String message,
                                      String envHealthWarning) {
}
