package com.sixbean.testpike.core.sched.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.sched.api.dto.SchedulerHealthInfo;
import com.sixbean.testpike.core.sched.domain.service.SchedulerHealthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/internal/scheduler")
public class SchedulerHealthInternalController {

    private final SchedulerHealthService healthService;

    public SchedulerHealthInternalController(SchedulerHealthService healthService) {
        this.healthService = healthService;
    }

    /** Paged health read; keyword filters by job name or plain-word description, sortBy/sortDir is the core whitelist key (blank = catalog order). */
    @GetMapping("/health")
    public Response<PageResult<SchedulerHealthInfo>> health(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(healthService.health(keyword, page, size, sortBy, sortDir));
    }
}
