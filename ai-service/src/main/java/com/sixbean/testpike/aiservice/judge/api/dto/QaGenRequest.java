package com.sixbean.testpike.aiservice.judge.api.dto;


public record QaGenRequest(Long projectId, Long documentId, String modelHint, Long operatorId) {
}
