package com.sixbean.testpike.admin.suites.api.dto;


public record SuiteRunResultView(long planId, String planName, int batchCount, int taskCount,
                                 int skippedArchived, String message) {
}
