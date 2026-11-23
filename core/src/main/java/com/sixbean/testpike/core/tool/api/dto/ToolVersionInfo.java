package com.sixbean.testpike.core.tool.api.dto;


public record ToolVersionInfo(
        Long toolId,
        int version,
        String reason,
        int statusAtSnapshot,
        String statusLabel,
        Long createdBy,
        String createdAt,
        String bodyPreview) {
}
