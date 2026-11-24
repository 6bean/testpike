package com.sixbean.testpike.admin.session.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.api.dto.ProbeOutcomeView;
import com.sixbean.testpike.admin.ai.api.dto.AiTaskEventView;
import com.sixbean.testpike.admin.session.api.dto.LoginStateRegisterRequest;
import com.sixbean.testpike.admin.session.api.dto.LoginStateView;
import com.sixbean.testpike.admin.session.infrastructure.remote.client.CoreLoginStateClient;
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

import java.util.List;

/**
 * Login-state ledger admin face .
 *
 * <p>Two doors per call: the function permission (perm:session family, one key
 * per capability as the AV design doc freezes) plus project data isolation —
 * view for reads, manage for writes (H1). Ownership for id-addressed calls is
 * taken from the core echo, never from the caller. Validation and D9
 * resolution stay single-sourced in the core service; this bridge only
 * authorizes and forwards (red line 6). The ledger never renders secret
 * values — plaintext has no path through this layer at all.
 */
@RestController
@RequestMapping("/api/v1/session-states")
public class LoginStateController {

    private final CoreLoginStateClient coreLoginStateClient;
    private final ProjectAccessGuard guard;

    public LoginStateController(CoreLoginStateClient coreLoginStateClient,
                                ProjectAccessGuard guard) {
        this.coreLoginStateClient = coreLoginStateClient;
        this.guard = guard;
    }

    /** Ledger list for one project (enabledOnly=true keeps usable rows; view door). */
    @GetMapping
    @RequirePermission("perm:session:read")
    public Response<List<LoginStateView>> list(@RequestParam long projectId,
                                               @RequestParam(required = false) Boolean enabledOnly) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(coreLoginStateClient.listStates(projectId, enabledOnly));
    }

    /**
 * Paged ledger segment for the management face (view door). Keyword matches
 * the state name or the account label; status 1=enabled, 0=disabled,
 * omitted=all; sortBy is restricted server-side to ledger columns, so a
 * header click can never fake a global order over the loaded pageful.
 */
    @GetMapping("/page")
    @RequirePermission("perm:session:read")
    public Response<PageView<LoginStateView>> listPage(@RequestParam long projectId,
                                                       @RequestParam(required = false) String keyword,
                                                       @RequestParam(required = false) Integer status,
                                                       @RequestParam(required = false) String sortBy,
                                                       @RequestParam(required = false) String sortDir,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(coreLoginStateClient.listStatesPage(
                projectId, keyword, status, sortBy, sortDir, page, size));
    }

    /** One ledger row (core honest 404 wording propagates; ownership from core echo). */
    @GetMapping("/{id}")
    @RequirePermission("perm:session:read")
    public Response<LoginStateView> get(@PathVariable long id) {
        LoginStateView state = coreLoginStateClient.findState(id);
        guard.assertCanView(UserContext.current(), state.projectId());
        return Response.builderSuccess(state);
    }

    /** Register/renew from a capture result (capture door + project manage gate). */
    @PostMapping
    @RequirePermission("perm:session:capture")
    public Response<Long> register(@RequestBody LoginStateRegisterRequest request) {
        guard.assertCanManage(UserContext.current(), request.projectId());
        return Response.builderSuccess(
                coreLoginStateClient.register(request.projectId(), request));
    }

    /**
     * Edit one ledger row in place (manage door). Ownership comes from the
     * core echo of the row, never from the body's projectId — a stale or
     * forged project id in the payload cannot steer the row across projects.
     * Validation, vault linkage and rename-uniqueness stay single-sourced in
     * core; this bridge only authorizes and forwards (red line 6).
     */
    @PutMapping("/{id}")
    @RequirePermission("perm:session:manage")
    public Response<Void> update(@PathVariable long id,
                                 @RequestBody LoginStateRegisterRequest request) {
        LoginStateView state = coreLoginStateClient.findState(id);
        guard.assertCanManage(UserContext.current(), state.projectId());
        coreLoginStateClient.update(id, request);
        return Response.builderSuccess(null);
    }

    /** Consumer-side D9 resolution for pickers and execution wiring (consume door). */
    @GetMapping("/resolve")
    @RequirePermission("perm:session:consume")
    public Response<LoginStateView> resolve(@RequestParam long projectId,
                                            @RequestParam(required = false) String stateName,
                                            @RequestParam(required = false) String roleScope) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(
                coreLoginStateClient.resolveState(projectId, stateName, roleScope));
    }

    /** Enable/disable (manage door; disable lands on the next session). */
    @PutMapping("/{id}/enabled")
    @RequirePermission("perm:session:manage")
    public Response<Void> setEnabled(@PathVariable long id, @RequestParam boolean enabled) {
        LoginStateView state = coreLoginStateClient.findState(id);
        guard.assertCanManage(UserContext.current(), state.projectId());
        coreLoginStateClient.setEnabled(id, enabled);
        return Response.builderSuccess(null);
    }

    /**
     * Keep-alive switch (manage door): ownership is read from the core echo
     * before forwarding, never from the caller. The synchronous double
     * pre-check lives in core; its refusal wording reaches the UI verbatim.
     */
    @org.springframework.web.bind.annotation.PatchMapping("/{id}/keepalive")
    @RequirePermission("perm:session:manage")
    public Response<Void> setKeepAlive(
            @PathVariable long id,
            @RequestBody com.sixbean.testpike.admin.session.api.dto.LoginStateKeepAliveRequest request) {
        LoginStateView state = coreLoginStateClient.findState(id);
        guard.assertCanManage(UserContext.current(), state.projectId());
        coreLoginStateClient.setKeepAlive(id, request);
        return Response.builderSuccess(null);
    }

    /** Remove the ledger row (manage door; vault row stays for audit). */
    @DeleteMapping("/{id}")
    @RequirePermission("perm:session:manage")
    public Response<Void> delete(@PathVariable long id) {
        LoginStateView state = coreLoginStateClient.findState(id);
        guard.assertCanManage(UserContext.current(), state.projectId());
        coreLoginStateClient.delete(id);
        return Response.builderSuccess(null);
    }

    /**
     * "Test now" (view door): replays this row's session against the first
     * recorded scope origin, or an explicit address for finer targeting.
     * Runs synchronously server-side; the UI tails the step trail afterwards.
     * The optional JSON payload carries the request shape (GET/POST/PUT plus
     * a body template whose {{CRED:key}} references resolve server-side at
     * send time) — nothing of it is stored; ownership still comes from the
     * core echo of the row, never from the caller.
     */
    @PostMapping("/{id}/probe")
    @RequirePermission("perm:session:read")
    public Response<ProbeOutcomeView> probe(@PathVariable long id,
                                            @RequestParam(required = false) String url,
                                            @RequestBody(required = false)
                                            com.sixbean.testpike.admin.session.api.dto.LoginStateProbeRunRequest run) {
        LoginStateView state = coreLoginStateClient.findState(id);
        guard.assertCanView(UserContext.current(), state.projectId());
        return Response.builderSuccess(coreLoginStateClient.probeState(id, url, run));
    }

    /** Step trail for one probe run (view door; rows are already masked). */
    @GetMapping("/{id}/probe-events")
    @RequirePermission("perm:session:read")
    public Response<List<AiTaskEventView>> probeEvents(@PathVariable long id,
                                                     @RequestParam String runToken,
                                                     @RequestParam(defaultValue = "0") long cursor) {
        LoginStateView state = coreLoginStateClient.findState(id);
        guard.assertCanView(UserContext.current(), state.projectId());
        return Response.builderSuccess(coreLoginStateClient.probeEvents(id, runToken, cursor));
    }

    /** Run history for the probe revisit entry (same view door). */
    @GetMapping("/{id}/probe-runs")
    @RequirePermission("perm:session:read")
    public Response<List<com.sixbean.testpike.admin.ai.api.dto.AiTaskRunView>> probeRuns(
            @PathVariable long id, @RequestParam(required = false) Integer limit) {
        LoginStateView state = coreLoginStateClient.findState(id);
        guard.assertCanView(UserContext.current(), state.projectId());
        return Response.builderSuccess(coreLoginStateClient.probeRuns(id, limit));
    }

    /** Keep-alive transition trail for one row (R67 revisit entry; masked
     *  rows, same view-door grammar as the probe trail — no second chain). */
    @GetMapping("/{id}/keepalive-events")
    @RequirePermission("perm:session:read")
    public Response<List<AiTaskEventView>> keepaliveEvents(@PathVariable long id,
                                                           @RequestParam String runToken,
                                                           @RequestParam(defaultValue = "0") long cursor) {
        LoginStateView state = coreLoginStateClient.findState(id);
        guard.assertCanView(UserContext.current(), state.projectId());
        return Response.builderSuccess(coreLoginStateClient.keepaliveEvents(id, runToken, cursor));
    }

    /** Keep-alive run history for the row-level process-detail revisit entry. */
    @GetMapping("/{id}/keepalive-runs")
    @RequirePermission("perm:session:read")
    public Response<List<com.sixbean.testpike.admin.ai.api.dto.AiTaskRunView>> keepaliveRuns(
            @PathVariable long id, @RequestParam(required = false) Integer limit) {
        LoginStateView state = coreLoginStateClient.findState(id);
        guard.assertCanView(UserContext.current(), state.projectId());
        return Response.builderSuccess(coreLoginStateClient.keepaliveRuns(id, limit));
    }

    
    @GetMapping("/{id}/probe-events/{eventId}/reveal")
    @RequirePermission("perm:session:read")
    public Response<com.sixbean.testpike.admin.project.api.dto.TrailRevealView> revealTrailField(
            @PathVariable long id, @PathVariable long eventId, @RequestParam String field) {
        LoginStateView state = coreLoginStateClient.findState(id);
        guard.assertCanView(UserContext.current(), state.projectId());
        return Response.builderSuccess(
                coreLoginStateClient.revealProbeTrailField(id, eventId, field));
    }

    
    @GetMapping("/{id}/probe-events/{eventId}/full")
    @RequirePermission("perm:session:read")
    public Response<com.sixbean.testpike.admin.project.api.dto.TrailFullTextView> trailFullText(
            @PathVariable long id, @PathVariable long eventId,
            @RequestParam(defaultValue = "0") int from,
            @RequestParam(required = false) Integer limit) {
        LoginStateView state = coreLoginStateClient.findState(id);
        guard.assertCanView(UserContext.current(), state.projectId());
        return Response.builderSuccess(
                coreLoginStateClient.probeTrailFullText(id, eventId, from, limit));
    }
}
