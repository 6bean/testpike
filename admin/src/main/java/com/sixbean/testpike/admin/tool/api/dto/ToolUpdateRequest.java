package com.sixbean.testpike.admin.tool.api.dto;


public record ToolUpdateRequest(
        int expectedVersion,
        String name,
        String category,
        String description,
        String body,
        String tagsJson,
        String inputSchemaJson,
        String outputSchemaJson,
        Integer execKind,
        String execSpecJson,
        Integer timeoutMs) {
}
