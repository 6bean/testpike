package com.sixbean.testpike.admin.skill.api.dto;


public record SkillUpdateRequest(
        int expectedVersion,
        String name,
        Integer type,
        String body,
        String frontmatterJson) {
}
