package com.sixbean.testpike.admin.skill.api.dto;


public record SkillRejectRequest(
        int expectedVersion,
        String reason) {
}
