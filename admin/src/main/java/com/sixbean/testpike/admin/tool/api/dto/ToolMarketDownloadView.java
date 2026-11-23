package com.sixbean.testpike.admin.tool.api.dto;


public record ToolMarketDownloadView(
        Long toolId,
        String format,
        String fileName,
        String objectKey,
        String url) {
}
