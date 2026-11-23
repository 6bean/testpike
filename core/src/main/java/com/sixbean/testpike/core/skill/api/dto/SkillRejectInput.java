package com.sixbean.testpike.core.skill.api.dto;


public record SkillRejectInput(
        int expectedVersion,
        String reason) {
}
