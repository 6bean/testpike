package com.sixbean.testpike.core.report.api.dto;


public record ExportDownloadView(long taskId, String fileName, String presignedUrl, int status) {
}
