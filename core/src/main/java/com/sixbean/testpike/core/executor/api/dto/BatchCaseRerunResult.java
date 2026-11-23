package com.sixbean.testpike.core.executor.api.dto;


public record BatchCaseRerunResult(long taskId, boolean created, String message) {
}
