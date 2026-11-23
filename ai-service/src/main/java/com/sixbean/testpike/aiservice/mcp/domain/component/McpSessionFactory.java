package com.sixbean.testpike.aiservice.mcp.domain.component;

import com.sixbean.testpike.aiservice.mcp.infrastructure.remote.McpAssemblyRemoteServer;


@FunctionalInterface
public interface McpSessionFactory {

    
    McpToolSession create(McpAssemblyRemoteServer server);
}
