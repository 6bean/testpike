package com.sixbean.testpike.core.skill.api.dto;


public record SkillCreateInput(
        Long projectId,
        String name,
        Integer type,
        String body,
        String frontmatterJson,
        Boolean submitNow) {
}
