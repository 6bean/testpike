package com.sixbean.testpike.admin.tool.api.dto;


public record ToolVersionInfoView(
        Long toolId,
        int version,
        String reason,
        int statusAtSnapshot,
        String statusLabel,
        Long createdBy,
        String createdAt,
        String bodyPreview) {
}
