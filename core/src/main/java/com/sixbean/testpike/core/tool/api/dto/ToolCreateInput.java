package com.sixbean.testpike.core.tool.api.dto;


public record ToolCreateInput(
        Long projectId,
        String name,
        Integer type,
        String category,
        String description,
        String body,
        String tagsJson,
        String inputSchemaJson,
        String outputSchemaJson,
        Integer execKind,
        String execSpecJson,
        Integer timeoutMs,
        Boolean submitNow) {
}
