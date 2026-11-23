package com.sixbean.testpike.core.requirement.domain.service;

import com.sixbean.testpike.core.requirement.api.dto.TraceChain;


public interface TraceChainService {

    
    TraceChain traceChainOfRequirement(long requirementId);

    
    TraceChain traceChainOfCase(long caseId);
}
