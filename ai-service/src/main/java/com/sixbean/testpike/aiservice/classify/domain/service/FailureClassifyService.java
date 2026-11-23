package com.sixbean.testpike.aiservice.classify.domain.service;

import com.sixbean.testpike.common.mq.ExecFailedEventMessage;


public interface FailureClassifyService {

    
    void classify(ExecFailedEventMessage message);
}
