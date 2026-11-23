package com.sixbean.testpike.aiservice.mcp.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.mcp.infrastructure.remote.McpAssemblyRemoteServer;

import java.net.http.HttpClient;
import java.time.Duration;


public class McpHttpSessionFactory implements McpSessionFactory {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public McpHttpSessionFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(McpToolServerClient.REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public McpToolSession create(McpAssemblyRemoteServer server) {
        return new McpToolServerClient(server, httpClient, objectMapper);
    }

    
    public Duration connectTimeout() {
        return McpToolServerClient.REQUEST_TIMEOUT;
    }
}
