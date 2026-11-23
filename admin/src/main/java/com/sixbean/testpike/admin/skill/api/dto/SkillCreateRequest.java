package com.sixbean.testpike.admin.skill.api.dto;


public record SkillCreateRequest(
        Long projectId,
        String name,
        Integer type,
        String body,
        String frontmatterJson,
        Boolean submitNow) {
}
