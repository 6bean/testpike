package com.sixbean.testpike.admin.tool.api.dto;


public record ToolRollbackRequest(
        int version,
        int expectedVersion) {
}
