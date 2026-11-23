package com.sixbean.testpike.core.skill.api.dto;


public record ProjectSkillBindingItem(
        Long skillId,
        Integer role,
        Integer priority,
        Integer enabled) {
}
