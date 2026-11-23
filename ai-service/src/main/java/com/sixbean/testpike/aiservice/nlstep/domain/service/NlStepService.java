package com.sixbean.testpike.aiservice.nlstep.domain.service;

import com.sixbean.testpike.aiservice.nlstep.api.dto.NlStepRequest;

import java.util.Map;


public interface NlStepService {

    
    Map<String, Object> generate(NlStepRequest request);
}
