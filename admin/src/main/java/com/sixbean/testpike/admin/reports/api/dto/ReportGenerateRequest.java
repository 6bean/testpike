package com.sixbean.testpike.admin.reports.api.dto;


public record ReportGenerateRequest(long projectId, int rangeType, Long planId,
                                    String startTime, String endTime, String title) {
}
