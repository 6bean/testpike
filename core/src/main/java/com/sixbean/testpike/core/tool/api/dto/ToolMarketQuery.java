package com.sixbean.testpike.core.tool.api.dto;


public record ToolMarketQuery(
        int page,
        int size,
        String keyword,
        Integer type,
        String category,
        String sortBy,
        String sortDir) {
}
