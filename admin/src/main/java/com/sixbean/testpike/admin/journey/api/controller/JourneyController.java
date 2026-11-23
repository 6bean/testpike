package com.sixbean.testpike.admin.journey.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.journey.api.dto.AddJourneySegmentRequest;
import com.sixbean.testpike.admin.journey.api.dto.CreateJourneyRequest;
import com.sixbean.testpike.admin.journey.api.dto.JourneyPageView;
import com.sixbean.testpike.admin.journey.api.dto.JourneyRunView;
import com.sixbean.testpike.admin.journey.api.dto.JourneySegmentView;
import com.sixbean.testpike.admin.journey.api.dto.JourneyView;
import com.sixbean.testpike.admin.journey.infrastructure.remote.client.CoreJourneyClient;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Journey admin endpoints — the user-facing half of the cross-project orchestration
 * bridge (admin /api/v1/journeys/** → core /internal/journeys/**, same topology as
 * ProgramController).
 * <p>Thin forwarding by design: plan validation, asset pinning, per-segment expansion
 * transactions, the owner-only exit sign-off and its audit row all live in core — the
 * admin layer adds login gating and permission keys only (JourneyBridgeTest pins the
 * whole-object pass-through contract).
 * <p>Permission: reuses the VIEW-family key {@code perm:project:project-view}, the same
 * zero-drift pattern as the program bridge; a dedicated journey permission family needs
 * RTM feature-point rows, registered by the closing package.
 */
@RestController
@RequestMapping("/api/v1/journeys")
public class JourneyController {

    private final CoreJourneyClient coreJourneyClient;

    public JourneyController(CoreJourneyClient coreJourneyClient) {
        this.coreJourneyClient = coreJourneyClient;
    }

    /** Create a journey plan under a program; the owner is the only future signer. */
    @RequirePermission("perm:project:project-view")
    @PostMapping
    public Response<Long> create(@RequestBody CreateJourneyRequest request) {
        requireLogin();
        return Response.builderSuccess(coreJourneyClient.createJourney(request));
    }

    /** Append one ordered segment to the plan (asset target or case target). */
    @RequirePermission("perm:project:project-view")
    @PostMapping("/{id}/segments")
    public Response<JourneySegmentView> addSegment(@PathVariable long id,
                                                   @RequestBody AddJourneySegmentRequest request) {
        requireLogin();
        return Response.builderSuccess(coreJourneyClient.addSegment(id, request));
    }

    /** Journey aggregate: header plus all segments ordered by seqNo. */
    @RequirePermission("perm:project:project-view")
    @GetMapping("/{id}")
    public Response<JourneyView> getJourney(@PathVariable long id) {
        requireLogin();
        return Response.builderSuccess(coreJourneyClient.getJourney(id));
    }

    /** Paged journey wall under a program (S-group worst-scale contract; keyword = name contains, judged in core). */
    @RequirePermission("perm:project:project-view")
    @GetMapping("/page")
    public Response<JourneyPageView> page(@RequestParam long programId,
                                          @RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "50") int pageSize,
                                          @RequestParam(required = false) String keyword) {
        requireLogin();
        return Response.builderSuccess(
                coreJourneyClient.pageJourneys(programId, page, pageSize, keyword));
    }

    /** Start a run: core expands segments into each owning project's batch, one transaction per segment. */
    @RequirePermission("perm:project:project-view")
    @PostMapping("/{id}/runs")
    public Response<JourneyRunView> startRun(@PathVariable long id) {
        requireLogin();
        return Response.builderSuccess(coreJourneyClient.startRun(id));
    }

    /** Run with all segment rows — the full-chain timeline traffic-light source. */
    @RequirePermission("perm:project:project-view")
    @GetMapping("/runs/{runId}")
    public Response<JourneyRunView> getRun(@PathVariable long runId) {
        requireLogin();
        return Response.builderSuccess(coreJourneyClient.getRun(runId));
    }

    /** Exit sign-off: rejected unless the caller is the journey owner and the run is terminal and unsigned. */
    @RequirePermission("perm:project:project-view")
    @PostMapping("/runs/{runId}/signoff")
    public Response<JourneyRunView> signOff(@PathVariable long runId) {
        requireLogin();
        return Response.builderSuccess(coreJourneyClient.signOff(runId));
    }

    /** Anonymous callers get the framework 401 before any core hop (same guard surface
     *  as the program bridge; kept as one private step, no duplication). */
    private void requireLogin() {
        UserContext.current();
    }
}
