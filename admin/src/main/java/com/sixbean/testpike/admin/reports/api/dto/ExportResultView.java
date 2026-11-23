package com.sixbean.testpike.admin.reports.api.dto;


public record ExportResultView(boolean async, long taskId, String fileName, byte[] content) {
}
