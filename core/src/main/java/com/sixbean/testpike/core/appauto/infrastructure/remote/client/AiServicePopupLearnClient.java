package com.sixbean.testpike.core.appauto.infrastructure.remote.client;

import com.sixbean.testpike.core.appauto.api.dto.AppautoPopupLearnRequest;

import java.util.Map;


public interface AiServicePopupLearnClient {

    
    Map<String, Object> recognize(AppautoPopupLearnRequest request);
}
