package com.sixbean.testpike.admin.executor.api.dto;


public record BatchCaseRerunView(long taskId, boolean created, String message) {
}
