package com.sixbean.testpike.admin.tool.api.dto;

import java.util.List;


public record ToolMarketItemView(
        Long id,
        Long projectId,
        String name,
        int type,
        String typeLabel,
        String category,
        String statusLabel,
        int currentVersion,
        String authorName,
        int downloadCount,
        int refProjectCount,
        int call30dCount,
        List<String> tags,
        String bodyPreview,
        String createdAt) {
}
