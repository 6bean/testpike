package com.sixbean.testpike.admin.webelement.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.webelement.api.dto.SiteProfileInput;
import com.sixbean.testpike.admin.webelement.api.dto.SiteProfileView;
import com.sixbean.testpike.common.webelement.SiteSurveyRunView;
import com.sixbean.testpike.admin.webelement.infrastructure.remote.client.CoreSiteProfileClient;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Site profile ledger API (whole-site survey drawer). Permission stays on the
 * keys the element ledger already ships — reads need project membership,
 * writes need the manage role on the row's own project, re-read server-side
 * and never trusted from the client. Deleting a profile keeps its survey runs
 * and collected elements; the refusal/lock semantics come from core intact.
 */
@RestController
@RequestMapping("/api/v1/site-profiles")
public class SiteProfileController {

    private final CoreSiteProfileClient client;
    private final ProjectAccessGuard guard;

    public SiteProfileController(CoreSiteProfileClient client, ProjectAccessGuard guard) {
        this.client = client;
        this.guard = guard;
    }

    /** Profiles of the current project segment, newest activity first. */
    @GetMapping
    @RequirePermission("perm:project:project-view")
    public Response<List<SiteProfileView>> list(@RequestParam long projectId) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(client.list(projectId));
    }

    /** One profile; membership is checked against the row's own project. */
    @GetMapping("/{id}")
    @RequirePermission("perm:project:project-view")
    public Response<SiteProfileView> get(@PathVariable long id) {
        SiteProfileView row = client.get(id);
        guard.assertCanView(UserContext.current(), row.projectId());
        return Response.builderSuccess(row);
    }

    /**
 * Survey-record rows for the drawer (newest first; core caps the segment).
 * The literal /runs path is matched ahead of /{id} by Spring's specificity
 * order, so the numeric-id door never swallows it.
 */
    @GetMapping("/runs")
    @RequirePermission("perm:project:project-view")
    public Response<List<SiteSurveyRunView>> listRuns(@RequestParam long projectId,
                                                      @RequestParam(defaultValue = "50") int limit) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(client.listRuns(projectId, limit));
    }

    /**
     * Dispatch a whole-site survey for one profile. The project is resolved by
     * re-reading the row (never taken from the browser), the manage gate
     * matches create/update, and core-side refusals (production host, frozen
     * rules) arrive with their guidance face intact.
     */
    @PostMapping("/{id}/survey")
    @RequirePermission("perm:project:project-view")
    public Response<Long> dispatchSurvey(@PathVariable long id) {
        SiteProfileView row = client.get(id);
        guard.assertCanManage(UserContext.current(), row.projectId());
        return Response.builderSuccess(client.dispatchSurvey(row.projectId(), id));
    }

    /** Create a profile inside the project picked in the header. */
    @PostMapping
    @RequirePermission("perm:project:project-view")
    public Response<SiteProfileView> create(@RequestBody SiteProfileInput input) {
        if (input == null || input.projectId() == null) {
            throw new ServiceException("尚未选择项目：请在顶栏项目下拉（未选时显示「请选择项目」）选定项目，再点「保存档案」", BizErrorCode.VALIDATION_ERROR);
        }
        guard.assertCanManage(UserContext.current(), input.projectId());
        return Response.builderSuccess(client.create(input));
    }

    /**
     * Update a profile. The row is re-read first so the manage gate runs on
     * the server-resolved project, and the revision token forwarded to core
     * keeps the stale-write refusal (40901) reachable from the browser.
     */
    @PutMapping("/{id}")
    @RequirePermission("perm:project:project-view")
    public Response<SiteProfileView> update(@PathVariable long id, @RequestBody SiteProfileInput input) {
        SiteProfileView row = client.get(id);
        guard.assertCanManage(UserContext.current(), row.projectId());
        return Response.builderSuccess(client.update(id, input));
    }

    /**
     * Delete a profile row. Survey runs and already-collected elements stay
     * filed (the deletion door only retires the scope definition).
     */
    @DeleteMapping("/{id}")
    @RequirePermission("perm:project:project-view")
    public Response<Void> delete(@PathVariable long id) {
        SiteProfileView row = client.get(id);
        guard.assertCanManage(UserContext.current(), row.projectId());
        client.delete(id);
        return Response.builderSuccess(null);
    }
}
