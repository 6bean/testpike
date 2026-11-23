package com.sixbean.testpike.aiservice.recordflow.domain.service;

import com.sixbean.testpike.aiservice.recordflow.api.dto.RecordFlowRequest;

import java.util.Map;


public interface RecordFlowService {

    
    Map<String, Object> generate(RecordFlowRequest request);
}
