package com.sixbean.testpike.admin.defects.api.dto;


public record DefectUpdateRequest(String title, String description, Integer severity, Long assigneeId,
                                  String stepsJson, String externalKey, Integer version) {
}
