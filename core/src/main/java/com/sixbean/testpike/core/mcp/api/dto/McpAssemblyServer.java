package com.sixbean.testpike.core.mcp.api.dto;

import java.util.List;

/**
 *
 * @param serverId tp_mcp_server.id
 */
public record McpAssemblyServer(
        Long serverId,
        String name,
        String endpoint,
        int transport,
        String auth,
        List<String> toolNames) {
}
