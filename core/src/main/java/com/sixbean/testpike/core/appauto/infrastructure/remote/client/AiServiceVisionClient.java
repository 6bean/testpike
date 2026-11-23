package com.sixbean.testpike.core.appauto.infrastructure.remote.client;

import com.sixbean.testpike.core.appauto.api.dto.AppautoVisualLocateRequest;

import java.util.Map;


public interface AiServiceVisionClient {

    
    Map<String, Object> locate(AppautoVisualLocateRequest request);
}
