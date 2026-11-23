package com.sixbean.testpike.aiservice.spec.domain.service;

import com.sixbean.testpike.common.mq.AiSpecExtractMessage;


public interface SpecDraftExtractService {

    
    void extract(AiSpecExtractMessage message);
}
