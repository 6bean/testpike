package com.sixbean.testpike.aiservice.mcp.domain.component;

import com.sixbean.testpike.aiservice.mcp.infrastructure.remote.McpAssemblyRemoteServer;

import java.util.List;


public interface McpToolSession {

    
    List<String> allowedTools();

    
    String connect();

    
    McpToolCallResult call(String toolName, String query);

    
    McpToolCallResult callWithArguments(String toolName, String argumentsJson);

    
    McpAssemblyRemoteServer server();
}
