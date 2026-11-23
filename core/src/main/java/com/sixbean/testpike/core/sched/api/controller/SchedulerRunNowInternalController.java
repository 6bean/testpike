package com.sixbean.testpike.core.sched.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.sched.domain.service.SchedulerRunNowService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;


@RestController
@RequestMapping("/internal/scheduler")
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulerRunNowInternalController {

    private final SchedulerRunNowService runNowService;

    public SchedulerRunNowInternalController(SchedulerRunNowService runNowService) {
        this.runNowService = runNowService;
    }

    
    @PostMapping("/run-now")
    public Response<String> runNow(@RequestParam String job) {
        runNowService.run(job, OperatorContext.currentOperatorId());
        return Response.builderSuccess("任务已执行完成：" + (job == null ? "" : job.trim()));
    }

    
    @GetMapping("/jobs")
    public Response<Set<String>> jobs() {
        return Response.builderSuccess(runNowService.jobNames());
    }
}
