package com.sixbean.testpike.core.mcp.api.dto;

import java.util.List;


public record McpAuthApplyResult(int updatedServers, List<String> details) {
}
