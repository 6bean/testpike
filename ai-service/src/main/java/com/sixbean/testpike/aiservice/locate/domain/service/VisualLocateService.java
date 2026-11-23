package com.sixbean.testpike.aiservice.locate.domain.service;

import com.sixbean.testpike.aiservice.locate.api.dto.VisualLocateRequest;

import java.util.Map;


public interface VisualLocateService {

    
    Map<String, Object> locate(VisualLocateRequest request);
}
