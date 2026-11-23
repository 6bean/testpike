package com.sixbean.testpike.core.skill.api.dto;

/**
 *
 * @param skillId SKILL id
 */
public record ProjectSkillItem(
        Long skillId,
        Integer role,
        Integer priority,
        Integer enabled,
        String boundAt,
        String name,
        String typeLabel) {
}
