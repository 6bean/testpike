package com.sixbean.testpike.admin.skill.api.dto;


public record SkillVersionView(
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
