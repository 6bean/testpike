package com.sixbean.testpike.core.tool.api.dto;


public record ToolVersionDetail(
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
