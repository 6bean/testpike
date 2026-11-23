package com.sixbean.testpike.core.tool.api.dto;


public record ProjectToolItem(
        Long toolId,
        Integer enabled,
        String boundAt,
        String name,
        Integer type,
        String typeLabel) {
}
