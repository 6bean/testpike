package com.sixbean.testpike.core.appauto.infrastructure.remote.client;

import com.sixbean.testpike.core.appauto.api.dto.AppautoNlStepLlmRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoNlStepView;


public interface AiServiceNlStepClient {

    
    AppautoNlStepView generate(AppautoNlStepLlmRequest request);
}
