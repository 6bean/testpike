package com.sixbean.testpike.core.testreport.api.dto;


public record ReportGenerateInput(long projectId, int rangeType, Long planId,
                                  String startTime, String endTime, String title) {
}
