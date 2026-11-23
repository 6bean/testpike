package com.sixbean.testpike.core.suites.api.dto;


public record SuiteRunResult(long planId, String planName, int batchCount, int taskCount,
                             int skippedArchived, String message) {
}
