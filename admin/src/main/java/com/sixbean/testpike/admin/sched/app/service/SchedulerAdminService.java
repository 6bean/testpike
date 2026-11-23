package com.sixbean.testpike.admin.sched.app.service;

import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.sched.api.dto.SchedulerHealthView;

import java.util.List;


public interface SchedulerAdminService {

    
    String run(String jobName, Long operatorId);

    
    List<String> jobs();

    /** Paged health read (core single source = shedlock full incl. ai-service lock rows; sort keys pass through to the core whitelist). */
    PageView<SchedulerHealthView> health(String keyword, int page, int size,
                                         String sortBy, String sortDir);
}
