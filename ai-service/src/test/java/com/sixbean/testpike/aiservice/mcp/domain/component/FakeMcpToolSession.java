package com.sixbean.testpike.aiservice.mcp.domain.component;

import com.sixbean.testpike.aiservice.mcp.infrastructure.remote.McpAssemblyRemoteServer;

import java.util.ArrayList;
import java.util.List;


public class FakeMcpToolSession implements McpToolSession {

    private final McpAssemblyRemoteServer server;
    private final String connectFailure;
    private final List<String> calls = new ArrayList<>();
    private String lastArguments;

    @Override
    public McpToolCallResult callWithArguments(String toolName, String argumentsJson) {
        if (!allowedTools().contains(toolName)) {
            return McpToolCallResult.fail(toolName, server.serverId(),
                    "工具未在白名单勾选集内（已拒绝）", 0L, null);
        }
        this.lastArguments = argumentsJson;
        calls.add(toolName + "|" + argumentsJson);
        return McpToolCallResult.ok(toolName, server.serverId(), argumentsJson, 12L, argumentsJson);
    }

    
    public String lastArguments() {
        return lastArguments;
    }

    public FakeMcpToolSession(McpAssemblyRemoteServer server, String connectFailure) {
        this.server = server;
        this.connectFailure = connectFailure;
    }

    @Override
    public McpAssemblyRemoteServer server() {
        return server;
    }

    @Override
    public List<String> allowedTools() {
        return server.toolNames();
    }

    @Override
    public String connect() {
        return connectFailure;
    }

    @Override
    public McpToolCallResult call(String toolName, String query) {
        calls.add(toolName + "|" + query);
        return McpToolCallResult.ok(toolName, server.serverId(),
                "结果 of " + toolName + " for " + query, 12L, "{\"q\":\"" + query + "\"}");
    }

    
    public List<String> calls() {
        return List.copyOf(calls);
    }
}
