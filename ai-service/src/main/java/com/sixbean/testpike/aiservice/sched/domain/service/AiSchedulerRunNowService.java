package com.sixbean.testpike.aiservice.sched.domain.service;

import java.util.Set;


public interface AiSchedulerRunNowService {

    
    void run(String jobName, Long operatorId);

    
    Set<String> jobNames();
}
