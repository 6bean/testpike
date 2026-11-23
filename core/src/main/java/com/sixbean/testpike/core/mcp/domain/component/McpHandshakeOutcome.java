package com.sixbean.testpike.core.mcp.domain.component;

import java.util.List;
import java.util.Map;


public record McpHandshakeOutcome(
        boolean success,
        McpProbeCategory category,
        String message,
        List<Map<String, Object>> tools,
        String serverName) {

    public static McpHandshakeOutcome fail(McpProbeCategory category, String message) {
        return new McpHandshakeOutcome(false, category, message, List.of(), null);
    }

    public static McpHandshakeOutcome ok(List<Map<String, Object>> tools, String serverName) {
        return new McpHandshakeOutcome(true, McpProbeCategory.OK,
                "连接成功（发现 " + tools.size() + " 个工具）", tools, serverName);
    }
}
