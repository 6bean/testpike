package com.sixbean.testpike.core.mcp.domain.service;

import com.sixbean.testpike.core.mcp.api.dto.McpAssemblyServer;
import com.sixbean.testpike.core.mcp.api.dto.McpInvocationInput;

import java.util.List;


public interface McpConsumptionService {

    
    List<McpAssemblyServer> getAssemblyServers();

    
    List<McpAssemblyServer> getAssemblyServers(Long projectId);

    
    int recordInvocations(List<McpInvocationInput> invocations);
}
