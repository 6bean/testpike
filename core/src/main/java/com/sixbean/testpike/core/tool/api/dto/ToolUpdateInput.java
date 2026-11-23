package com.sixbean.testpike.core.tool.api.dto;


public record ToolUpdateInput(
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
