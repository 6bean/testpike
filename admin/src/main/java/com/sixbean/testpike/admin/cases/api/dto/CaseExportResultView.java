package com.sixbean.testpike.admin.cases.api.dto;


public record CaseExportResultView(boolean async, long taskId, String fileName, byte[] content,
                                   int total, int removed) {
}
