package com.sixbean.testpike.admin.apitest.api.controller;

import com.sixbean.testpike.admin.apitest.api.dto.ResponseCompareView;
import com.sixbean.testpike.admin.apitest.api.dto.RunCreateRequest;
import com.sixbean.testpike.admin.apitest.api.dto.RunView;
import com.sixbean.testpike.admin.apitest.app.component.ApiTestAccessResolver;
import com.sixbean.testpike.admin.apitest.infrastructure.remote.client.CoreApiRunClient;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/api/v1/apitest/runs")
public class ApiRunController {

    private final CoreApiRunClient coreApiRunClient;
    private final ApiTestAccessResolver accessResolver;
    private final ProjectAccessGuard guard;

    public ApiRunController(CoreApiRunClient coreApiRunClient,
                            ApiTestAccessResolver accessResolver,
                            ProjectAccessGuard guard) {
        this.coreApiRunClient = coreApiRunClient;
        this.accessResolver = accessResolver;
        this.guard = guard;
    }

    
    @PostMapping
    @RequirePermission("perm:detail:case-tag")
    public Response<RunView> create(@RequestBody RunCreateRequest request) {
        guard.assertCanWriteCase(UserContext.current(),
                accessResolver.projectIdOfRequest(request.requestId()));
        return Response.builderSuccess(coreApiRunClient.createRun(request));
    }

    
    @GetMapping("/{id}")
    @RequirePermission("perm:detail:case-tag")
    public Response<RunView> get(@PathVariable long id) {
        RunView run = coreApiRunClient.getRun(id);
        guard.assertCanView(UserContext.current(), accessResolver.projectIdOfRequest(run.requestId()));
        return Response.builderSuccess(run);
    }

    
    @GetMapping
    @RequirePermission("perm:detail:case-tag")
    public Response<List<RunView>> listByRequest(@RequestParam long requestId) {
        guard.assertCanView(UserContext.current(), accessResolver.projectIdOfRequest(requestId));
        return Response.builderSuccess(coreApiRunClient.listRunsByRequest(requestId));
    }

    /**
     * Compare two runs' recorded response bodies (W-c5 run history).
     * Both sides must belong to projects the caller can view — a non-member
     * never reaches core's compare (H1 isolation, guard runs before the hop);
     * the tri-state decision and diff vocabulary stay in core (single source).
     */
    @GetMapping("/compare")
    @RequirePermission("perm:detail:case-tag")
    public Response<ResponseCompareView> compare(@RequestParam long left, @RequestParam long right) {
        RunView leftRun = coreApiRunClient.getRun(left);
        guard.assertCanView(UserContext.current(), accessResolver.projectIdOfRequest(leftRun.requestId()));
        RunView rightRun = coreApiRunClient.getRun(right);
        guard.assertCanView(UserContext.current(), accessResolver.projectIdOfRequest(rightRun.requestId()));
        return Response.builderSuccess(coreApiRunClient.compareRuns(left, right));
    }

    
    @DeleteMapping("/{id}")
    @RequirePermission("perm:detail:case-tag")
    public Response<Void> delete(@PathVariable long id) {
        RunView run = coreApiRunClient.getRun(id);
        guard.assertCanWriteCase(UserContext.current(), accessResolver.projectIdOfRequest(run.requestId()));
        coreApiRunClient.deleteRun(id);
        return Response.builderSuccess(null);
    }

    
    @DeleteMapping
    @RequirePermission("perm:detail:case-tag")
    public Response<Void> clear(@RequestParam long requestId) {
        guard.assertCanWriteCase(UserContext.current(), accessResolver.projectIdOfRequest(requestId));
        coreApiRunClient.clearRuns(requestId);
        return Response.builderSuccess(null);
    }
}
