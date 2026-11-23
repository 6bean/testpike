package com.sixbean.testpike.core.spec.domain.service;

import com.sixbean.testpike.core.spec.api.dto.SpecExtractEstimate;


public interface SpecExtractDispatchService {

    
    SpecExtractEstimate dispatch(long operatorId, long documentId, String modelHint);
}
