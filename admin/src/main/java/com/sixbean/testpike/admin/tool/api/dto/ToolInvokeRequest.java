package com.sixbean.testpike.admin.tool.api.dto;


public record ToolInvokeRequest(Long taskId, String input, String confirmToken) {
}
