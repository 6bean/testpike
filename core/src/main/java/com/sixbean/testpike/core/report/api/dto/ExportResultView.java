package com.sixbean.testpike.core.report.api.dto;


public record ExportResultView(boolean async, long taskId, String fileName, byte[] content) {
}
