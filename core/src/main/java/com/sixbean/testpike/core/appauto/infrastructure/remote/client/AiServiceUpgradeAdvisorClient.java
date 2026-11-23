package com.sixbean.testpike.core.appauto.infrastructure.remote.client;

import com.sixbean.testpike.core.appauto.api.dto.AppautoUpgradeAdvisorLlmRequest;

import java.util.Map;


public interface AiServiceUpgradeAdvisorClient {

    
    Map<String, Object> advise(AppautoUpgradeAdvisorLlmRequest request);
}
