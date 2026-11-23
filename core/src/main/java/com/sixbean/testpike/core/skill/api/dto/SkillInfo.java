package com.sixbean.testpike.core.skill.api.dto;


public record SkillInfo(
        Long id,
        Long projectId,
        String name,
        int type,
        String typeLabel,
        int status,
        String statusLabel,
        int currentVersion,
        int version,
        String bodyPreview,
        String frontmatterJson,
        Long createdBy,
        Long approvedBy,
        String approvedAt,
        String rejectReason,
        String createdAt,
        String updatedAt) {
}
