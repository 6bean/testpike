package com.sixbean.testpike.admin.skill.api.dto;


public record ProjectSkillItem(
        Long skillId,
        Integer role,
        Integer priority,
        Integer enabled,
        String boundAt,
        String name,
        String typeLabel) {
}
