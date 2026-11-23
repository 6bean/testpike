package com.sixbean.testpike.api.mcp.infrastructure.remote;


public interface ApiTokenVerifyClient {

    
    McpVerifyOutcome verify(String plaintext);
}
