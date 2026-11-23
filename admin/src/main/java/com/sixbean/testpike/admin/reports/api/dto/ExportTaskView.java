package com.sixbean.testpike.admin.reports.api.dto;


public record ExportTaskView(long id, String reportType, int masked, int status, String fileKey,
                             Integer rowCount, String errorDetail,
                             String createdAt, String finishedAt) {
}
