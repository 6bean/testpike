package com.sixbean.testpike.admin.tool.api.dto;


public record ToolRejectRequest(
        int expectedVersion,
        String reason) {
}
