package com.sixbean.testpike.core.skill.api.dto;


public record SkillUpdateInput(
        int expectedVersion,
        String name,
        Integer type,
        String body,
        String frontmatterJson) {
}
