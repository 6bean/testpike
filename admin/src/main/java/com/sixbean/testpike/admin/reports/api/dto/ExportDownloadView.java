package com.sixbean.testpike.admin.reports.api.dto;


public record ExportDownloadView(long taskId, String fileName, String presignedUrl, int status) {
}
