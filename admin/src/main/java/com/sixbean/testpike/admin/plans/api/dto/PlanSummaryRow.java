package com.sixbean.testpike.admin.plans.api.dto;


public record PlanSummaryRow(long planId, String planName, int status, String statusLabel,
                             long caseCount, long passed, long failed, long notRun,
                             long running, long skipped, double passRate,
                             String planStart, String planEnd) {
}
