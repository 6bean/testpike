package com.sixbean.testpike.admin.sched.infrastructure.remote.client;

import java.util.Set;


public interface SchedulerAiClient {

    
    String runNow(String job, Long operatorId);

    
    Set<String> jobs();
}
