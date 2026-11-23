package com.sixbean.testpike.core.audit.domain.service;

import com.sixbean.testpike.common.clientobject.ChainVerifyResult;


public interface AuditChainService {

    
    ChainVerifyResult verify(long operatorId);

    
    ChainVerifyResult taskResult(long taskId);
}
