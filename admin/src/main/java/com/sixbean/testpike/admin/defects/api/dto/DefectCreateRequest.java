package com.sixbean.testpike.admin.defects.api.dto;


public record DefectCreateRequest(Long projectId, Long caseId, Long planId, String title, String description,
                                  Integer severity, Long assigneeId, String stepsJson, String externalKey) {
}
