package com.sixbean.testpike.admin.tool.api.dto;


public record ToolDetailView(
        Long id,
        Long projectId,
        String name,
        int type,
        String typeLabel,
        String category,
        String description,
        String body,
        String tagsJson,
        String inputSchemaJson,
        String outputSchemaJson,
        Integer execKind,
        String execKindLabel,
        String execSpecJson,
        int timeoutMs,
        int status,
        String statusLabel,
        int currentVersion,
        int version,
        Long createdBy,
        Long approvedBy,
        String approvedAt,
        String rejectReason,
        String createdAt,
        String updatedAt) {
}
