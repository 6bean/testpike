package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.core.appauto.api.dto.AppautoVisualLocateRequest;

import java.util.Map;


public interface AppautoVisualLocateService {

    
    Map<String, Object> locate(AppautoVisualLocateRequest request);
}
