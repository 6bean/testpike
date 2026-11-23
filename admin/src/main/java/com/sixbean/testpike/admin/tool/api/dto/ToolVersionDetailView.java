package com.sixbean.testpike.admin.tool.api.dto;


public record ToolVersionDetailView(
        Long toolId,
        int version,
        String body,
        String inputSchemaJson,
        String outputSchemaJson,
        String execSpecJson,
        Integer timeoutMs,
        int statusAtSnapshot,
        String statusLabel,
        String reason,
        Long createdBy,
        String createdAt) {
}
