package com.sixbean.testpike.aiservice.gen.domain.service;

import com.sixbean.testpike.common.mq.AiGenMessage;


public interface AiGenService {

    
    void generate(AiGenMessage message);
}
