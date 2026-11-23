package com.sixbean.testpike.core.defects.api.dto;


public record DefectCreateInput(Long projectId, Long caseId, Long planId, String title, String description,
                                Integer severity, Long assigneeId, String stepsJson, String externalKey) {
}
