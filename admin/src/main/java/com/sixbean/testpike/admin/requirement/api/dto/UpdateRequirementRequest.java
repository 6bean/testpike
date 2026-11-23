package com.sixbean.testpike.admin.requirement.api.dto;


public record UpdateRequirementRequest(String title, Integer source, String sourceId,
                                       String acceptanceCriteria, String chapter, Integer status,
                                       Integer expectedVersion) {
}
