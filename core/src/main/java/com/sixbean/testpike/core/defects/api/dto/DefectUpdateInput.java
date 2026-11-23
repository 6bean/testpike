package com.sixbean.testpike.core.defects.api.dto;


public record DefectUpdateInput(String title, String description, Integer severity, Long assigneeId,
                                String stepsJson, String externalKey, Integer version) {
}
