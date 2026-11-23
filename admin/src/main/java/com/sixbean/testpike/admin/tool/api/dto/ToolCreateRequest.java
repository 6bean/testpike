package com.sixbean.testpike.admin.tool.api.dto;


public record ToolCreateRequest(
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
