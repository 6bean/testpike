package com.sixbean.testpike.core.reportcenter.api.dto;


public record ReportExportFile(String fileName, String contentType, byte[] content) {
}
