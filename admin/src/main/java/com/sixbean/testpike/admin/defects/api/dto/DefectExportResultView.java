package com.sixbean.testpike.admin.defects.api.dto;


public record DefectExportResultView(boolean async, long taskId, String fileName, byte[] content,
                                     int total, int removed) {
}
