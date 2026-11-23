package com.sixbean.testpike.aiservice.mcp.infrastructure.remote;

import java.util.List;

/**
 *
 * @param serverId tp_mcp_server.id
 */
public record McpAssemblyRemoteServer(
        long serverId,
        String name,
        String endpoint,
        int transport,
        String auth,
        List<String> toolNames) {
}
