package com.sixbean.testpike.admin.appauto.api.controller;

import com.sixbean.testpike.admin.appauto.api.dto.AppautoElementCaptureInput;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoElementInput;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoElementPageView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoElementView;
import com.sixbean.testpike.admin.appauto.infrastructure.remote.client.CoreAppautoClient;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Element library management surface (/api/v1/appauto/elements family, W5 app
 * automation phase two, bridged to the core element API; same posture as
 * AppautoPopupRuleController and AppautoDeviceController).
 * <p>Gate layout: listing and single read take the project-view permission
 * plus an access check against the project id (reads check the owner echoed
 * by core, never a client claim); create/update/delete take the manage gate —
 * create against the target project from the query parameter, update/delete
 * against the row owner re-resolved from core first. Thin bridge only: every
 * validation (locator type value set, confidence range, per-project unique
 * name, missing-row 404 wording) lives in the core service; core rejections
 * travel to the caller unmutated (no second validation layer).
 */
@RestController
@RequestMapping("/api/v1/appauto")
public class AppautoElementController {

    private final CoreAppautoClient coreAppautoClient;
    private final ProjectAccessGuard guard;

    public AppautoElementController(CoreAppautoClient coreAppautoClient,
                                    ProjectAccessGuard guard) {
        this.coreAppautoClient = coreAppautoClient;
        this.guard = guard;
    }

    /** Paginated listing, newest-first, keyword + locator-type filters. */
    @GetMapping("/elements")
    @RequirePermission("perm:project:project-view")
    public Response<AppautoElementPageView> page(@RequestParam long projectId,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String locatorType,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int pageSize) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(
                coreAppautoClient.pageElements(projectId, keyword, locatorType, page, pageSize));
    }

    /** Single row read (view gate against the core-echoed owner; missing = core 404 passthrough). */
    @GetMapping("/elements/{id}")
    @RequirePermission("perm:project:project-view")
    public Response<AppautoElementView> get(@PathVariable long id) {
        AppautoElementView row = coreAppautoClient.findElement(id);
        guard.assertCanView(UserContext.current(), row.projectId());
        return Response.builderSuccess(row);
    }

    /** Create one element profile (manage gate on the target project; returns the new id). */
    @PostMapping("/elements")
    @RequirePermission("perm:project:project-view")
    public Response<Long> create(@RequestParam long projectId,
                                 @RequestBody AppautoElementInput input) {
        guard.assertCanManage(UserContext.current(), projectId);
        return Response.builderSuccess(coreAppautoClient.createElement(projectId, input));
    }

    /**
     * File a live-frame crop as a new image element (collaborative capture:
     * the operator selects a region on the monitor view; manage gate on the
     * target project, region/frame rejections travel from core unmutated).
     */
    @PostMapping("/elements/capture")
    @RequirePermission("perm:project:project-view")
    public Response<Long> capture(@RequestParam long projectId,
                                  @RequestBody AppautoElementCaptureInput input) {
        guard.assertCanManage(UserContext.current(), projectId);
        return Response.builderSuccess(coreAppautoClient.captureElement(projectId, input));
    }

    /** Full update of one profile (ownership re-resolved from core, manage gate is strict). */
    @PutMapping("/elements/{id}")
    @RequirePermission("perm:project:project-view")
    public Response<AppautoElementView> update(@PathVariable long id,
                                               @RequestBody AppautoElementInput input) {
        AppautoElementView row = coreAppautoClient.findElement(id);
        guard.assertCanManage(UserContext.current(), row.projectId());
        return Response.builderSuccess(coreAppautoClient.updateElement(id, input));
    }

    /** Delete one profile (manage gate against the core-echoed owner; core conflict wording passes through). */
    @DeleteMapping("/elements/{id}")
    @RequirePermission("perm:project:project-view")
    public Response<Void> delete(@PathVariable long id) {
        AppautoElementView row = coreAppautoClient.findElement(id);
        guard.assertCanManage(UserContext.current(), row.projectId());
        coreAppautoClient.deleteElement(id);
        return Response.builderSuccess(null);
    }
}
