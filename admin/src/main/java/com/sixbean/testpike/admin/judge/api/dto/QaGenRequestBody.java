package com.sixbean.testpike.admin.judge.api.dto;


public record QaGenRequestBody(Long projectId, Long documentId, String modelHint, Long operatorId) {
}
