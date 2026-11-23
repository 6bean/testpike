package com.sixbean.testpike.core.skill.api.dto;


public record SkillUsageInput(
        Long taskId,
        Long skillId,
        Integer version,
        Integer stage,
        Integer dropped) {
}
