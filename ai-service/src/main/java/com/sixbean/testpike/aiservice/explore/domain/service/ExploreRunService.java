package com.sixbean.testpike.aiservice.explore.domain.service;

import com.sixbean.testpike.common.mq.ExploreRunMessage;


public interface ExploreRunService {

    
    void run(ExploreRunMessage message);
}
