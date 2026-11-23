package com.sixbean.testpike.aiservice.sample.domain.service;

import com.sixbean.testpike.common.mq.CaseIngestEventMessage;


public interface TrainingSampleService {

    
    void captureSample(CaseIngestEventMessage message);
}
