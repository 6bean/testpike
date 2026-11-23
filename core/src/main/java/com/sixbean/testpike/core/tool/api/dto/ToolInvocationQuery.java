package com.sixbean.testpike.core.tool.api.dto;


public record ToolInvocationQuery(
        int page,
        int size,
        long toolId,
        String start,
        String end) {
}
