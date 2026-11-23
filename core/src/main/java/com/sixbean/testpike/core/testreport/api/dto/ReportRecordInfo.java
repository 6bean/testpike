package com.sixbean.testpike.core.testreport.api.dto;


public record ReportRecordInfo(long id, long projectId, Long planId, String planName, String title,
                               int rangeType, String rangeLabel, String createdByName, String createdAt) {
}
