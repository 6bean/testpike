package com.sixbean.testpike.core.sched.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.sched.api.dto.ScheduleFireInfo;
import com.sixbean.testpike.core.sched.api.dto.ScheduleFireQuery;
import com.sixbean.testpike.core.sched.domain.service.ScheduleFireService;
import com.sixbean.testpike.core.sched.domain.component.ScheduleCronTimes;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;


@RestController
@RequestMapping("/internal/schedule")
public class ScheduleInternalController {

    private final ScheduleFireService scheduleFireService;

    public ScheduleInternalController(ScheduleFireService scheduleFireService) {
        this.scheduleFireService = scheduleFireService;
    }

    /** Trigger ledger list (H1 project isolation, kind/status filters, whitelisted sort, paging). */
    @GetMapping("/fires")
    public Response<PageResult<ScheduleFireInfo>> listFires(@RequestParam long projectId,
                                                            @RequestParam(required = false) String kind,
                                                            @RequestParam(required = false) String status,
                                                            @RequestParam(defaultValue = "1") int page,
                                                            @RequestParam(defaultValue = "20") int size,
                                                            @RequestParam(required = false) String sortBy,
                                                            @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(
                scheduleFireService.listFires(new ScheduleFireQuery(
                        projectId, kind, status, page, size, sortBy, sortDir)));
    }

    /**
     * Next-fire preview (B-8 wizard scheduled section): the next {@code count}
     * theoretical trigger instants strictly after server time for the given cron,
     * ascending. Same ScheduleCronTimes single source as the firing scanner — the
     * preview shows exactly what the scanner will do. Blank cron = legal "not
     * scheduled" state and previews an empty list; an invalid expression is
     * refused fail-closed with the human-readable 6-field wording.
     */
    @GetMapping("/cron-preview")
    public Response<List<Instant>> previewCron(@RequestParam(required = false) String cron,
                                               @RequestParam(defaultValue = "3") int count) {
        return Response.builderSuccess(
                ScheduleCronTimes.nextFires(cron, Instant.now(), count));
    }
}
