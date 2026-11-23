package com.sixbean.testpike.core.judge.domain.service;

import com.sixbean.testpike.core.judge.api.dto.DispatchReceipt;


public interface JudgeDispatchService {

    
    DispatchReceipt dispatch(long runId, String evaluatedProvider);
}
