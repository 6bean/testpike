package com.sixbean.testpike.core.appauto.infrastructure.remote.client;

import com.sixbean.testpike.core.appauto.api.dto.AppautoWxMockLlmRequest;

import java.util.Map;


public interface AiServiceWxMockClient {

    
    Map<String, Object> generate(AppautoWxMockLlmRequest request);
}
