package com.sixbean.testpike.admin.skill.api.dto;


public record ProjectSkillBindingItem(
        Long skillId,
        Integer role,
        Integer priority,
        Integer enabled) {
}
