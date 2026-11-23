package com.sixbean.testpike.core.skill.api.dto;


public record SkillVersionInfo(
        Long skillId,
        int version,
        String reason,
        int statusAtSnapshot,
        String statusLabel,
        String approvedAt,
        Long createdBy,
        String createdAt,
        String bodyPreview) {
}
