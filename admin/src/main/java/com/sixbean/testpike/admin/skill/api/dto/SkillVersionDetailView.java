package com.sixbean.testpike.admin.skill.api.dto;


public record SkillVersionDetailView(
        Long skillId,
        int version,
        String body,
        String frontmatterJson,
        String reason,
        int statusAtSnapshot,
        String statusLabel,
        String approvedAt,
        Long createdBy,
        String createdAt) {
}
