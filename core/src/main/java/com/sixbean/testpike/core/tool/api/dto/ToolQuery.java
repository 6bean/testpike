package com.sixbean.testpike.core.tool.api.dto;


public record ToolQuery(
        int page,
        int size,
        String keyword,
        Integer type,
        Integer status,
        Long projectId,
        String category,
        Long tagId,
        String sortBy,
        String sortDir) {

    
    public ToolQuery(int page, int size, String keyword, Integer type, Integer status,
                     Long projectId, String category, String sortBy, String sortDir) {
        this(page, size, keyword, type, status, projectId, category, null, sortBy, sortDir);
    }
}
