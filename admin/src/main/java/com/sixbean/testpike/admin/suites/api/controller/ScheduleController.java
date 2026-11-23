package com.sixbean.testpike.admin.suites.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.suites.api.dto.ScheduleFireView;
import com.sixbean.testpike.admin.suites.infrastructure.remote.client.CoreScheduleClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;


@RestController
@RequestMapping("/api/v1/schedule")
public class ScheduleController {

    private final CoreScheduleClient coreScheduleClient;
    private final ProjectAccessGuard guard;

    public ScheduleController(CoreScheduleClient coreScheduleClient, ProjectAccessGuard guard) {
        this.coreScheduleClient = coreScheduleClient;
        this.guard = guard;
    }

    /** Trigger ledger list (project members; kind/status filters; whitelisted sortBy/sortDir optional). */
    @GetMapping("/fires")
    public Response<PageView<ScheduleFireView>> listFires(@RequestParam long projectId,
                                                          @RequestParam(required = false) String kind,
                                                          @RequestParam(required = false) String status,
                                                          @RequestParam(defaultValue = "1") int page,
                                                          @RequestParam(defaultValue = "20") int size,
                                                          @RequestParam(required = false) String sortBy,
                                                          @RequestParam(required = false) String sortDir) {
        CurrentUser user = UserContext.current();
        guard.assertCanView(user, projectId);
        return Response.builderSuccess(
                coreScheduleClient.listFires(projectId, kind, status, page, size, sortBy, sortDir));
    }

    /**
     * Next-fire preview for the wizard's scheduled section (B-8): forwards
     * cron/count to core untouched — the trigger-time arithmetic is single
     * sourced in core ScheduleCronTimes, same source the firing scanner uses.
     * Read-only pure computation, so no project resource is accessed here and
     * no H1 guard applies (any authenticated user may preview a cron).
     */
    @GetMapping("/cron-preview")
    public Response<List<Instant>> previewCron(@RequestParam(required = false) String cron,
                                               @RequestParam(defaultValue = "3") int count) {
        UserContext.current();
        return Response.builderSuccess(coreScheduleClient.previewCron(cron, count));
    }
}
