package com.sixbean.testpike.admin.mcp.api.dto;

import java.util.List;


public record McpAuthApplyRequest(List<Long> serverIds) {
}
