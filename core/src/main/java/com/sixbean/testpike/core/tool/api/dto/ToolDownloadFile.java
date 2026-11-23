package com.sixbean.testpike.core.tool.api.dto;


public record ToolDownloadFile(
        Long toolId,
        int version,
        String fileName,
        String contentType,
        byte[] content) {
}
