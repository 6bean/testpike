package com.sixbean.testpike.core.tool.api.dto;

/**
 *
 * @param format md / zip
 */
public record ToolMarketDownloadView(
        Long toolId,
        String format,
        String fileName,
        String objectKey,
        String url) {
}
