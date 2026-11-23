package com.sixbean.testpike.api.mcp.infrastructure.remote;

import java.util.List;


public interface ToolInvocationRecordClient {

    
    void record(List<McpInvocationRemoteInput> invocations);
}
