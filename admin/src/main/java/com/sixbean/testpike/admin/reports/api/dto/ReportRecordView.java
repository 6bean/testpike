package com.sixbean.testpike.admin.reports.api.dto;


public record ReportRecordView(long id, long projectId, Long planId, String planName, String title,
                               int rangeType, String rangeLabel, String createdByName, String createdAt) {
}
