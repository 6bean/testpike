package com.sixbean.testpike.core.mcp.domain.service;

import com.sixbean.testpike.core.mcp.api.dto.McpProbeResult;


public interface McpConnectivityService {

    
    McpProbeResult test(Long operatorId, Long id);
}
