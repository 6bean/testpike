package com.sixbean.testpike.core.sched.domain.service;

import java.util.Set;


public interface SchedulerRunNowService {

    
    void run(String jobName, Long operatorId);

    
    Set<String> jobNames();
}
