package com.sixbean.testpike.core.sched.domain.service;

import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.sched.api.dto.ScheduleFireInfo;
import com.sixbean.testpike.core.sched.api.dto.ScheduleFireQuery;

import java.time.Instant;


public interface ScheduleFireService {

    
    String KIND_SUITE = "suite";
    
    String KIND_TEMPLATE = "template";
    /** Periodic perf-scenario trigger kind (B-8: nightly low-peak capacity regressions). */
    String KIND_PERF = "perf";

    
    int scanAndFire(Instant now);

    
    boolean tryFire(String kind, long refId, long projectId, Instant fireTime);

    
    void recordResult(String kind, long refId, Instant fireTime, String status, String message);

    
    PageResult<ScheduleFireInfo> listFires(ScheduleFireQuery query);
}
