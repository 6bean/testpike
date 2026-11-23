package com.sixbean.testpike.admin.requirement.api.dto;


public record CreateRequirementRequest(Integer source, String sourceId, String title,
                                       String acceptanceCriteria, String chapter) {
}
