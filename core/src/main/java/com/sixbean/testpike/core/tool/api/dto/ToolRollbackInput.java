package com.sixbean.testpike.core.tool.api.dto;


public record ToolRollbackInput(
        int version,
        int expectedVersion) {
}
