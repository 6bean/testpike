package com.sixbean.testpike.core.appauto.infrastructure.remote.client;

import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixSampleLlmRequest;

import java.util.Map;


public interface AiServiceMatrixSampleClient {

    
    Map<String, Object> sample(AppautoMatrixSampleLlmRequest request);
}
