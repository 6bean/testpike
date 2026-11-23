package com.sixbean.testpike.core.events.domain.service;

import com.sixbean.testpike.core.events.api.dto.CaseEventMessage;


public interface CaseEventPublisher {

    
    void publish(CaseEventMessage message);
}
