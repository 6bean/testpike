package com.sixbean.testpike.core.appauto.infrastructure.remote.client;

import com.sixbean.testpike.core.appauto.api.dto.AppautoRecordFlowLlmRequest;

import java.util.List;
import java.util.Map;


public interface AiServiceRecordFlowClient {

    
    List<Map<String, Object>> generate(AppautoRecordFlowLlmRequest request);
}
