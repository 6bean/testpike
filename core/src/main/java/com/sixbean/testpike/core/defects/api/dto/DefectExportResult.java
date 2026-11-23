package com.sixbean.testpike.core.defects.api.dto;


public record DefectExportResult(boolean async, long taskId, String fileName, byte[] content,
                                 int total, int removed) {
}
