package com.sixbean.testpike.core.perf.domain.service;


public interface PerfRunStopService {

    
    int stopRun(long projectId, long runId, Long operatorId);

    
    int stopAll(long projectId, Long operatorId);
}
