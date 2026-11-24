package com.sixbean.testpike.core.session.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.session.api.dto.LoginStateInfo;
import com.sixbean.testpike.core.session.api.dto.LoginStateInput;
import com.sixbean.testpike.core.session.domain.component.SessionStateSupport;
import com.sixbean.testpike.core.session.domain.service.LoginStateService;
import com.sixbean.testpike.core.session.infrastructure.dataobject.LoginState;
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
import com.sixbean.testpike.core.session.domain.service.LoginStateProbeService;
import com.sixbean.testpike.core.session.domain.service.LoginStateProbeService.ProbeResult;
import com.sixbean.testpike.core.session.domain.service.impl.LoginStateProbeServiceImpl;
import com.sixbean.testpike.core.session.domain.component.LoginStateKeepAliveRunner;
import com.sixbean.testpike.core.events.domain.component.TaskEventWindowReader;

/**
 * Login-state ledger internal endpoints (core service layer, called by admin
 * over HTTP; same layering as CredentialInternalController).
 *
 * <p>Authorization (perm:session family) and the project data gate run on the
 * admin side; audit sits on the service layer — this controller keeps none of
 * it (red line 6, one implementation per concern). All validation and the D9
 * resolution semantics stay single-sourced in the core service and
 * SessionStateSupport.
 *
 * <p>This layer has NO plaintext door: revealStateValue is consumed only
 * inside the core JVM at dispatch/consumption time and is never exposed as an
 * HTTP endpoint (never-echo red line). The verification write-back likewise is
 * a server-side signal — it is intentionally absent here so no caller can
 * forge a "verified" outcome through the bridge.
 */
@RestController
@RequestMapping("/internal/session-states")
public class LoginStateInternalController {

    private final LoginStateService loginStateService;
    private final SessionStateSupport sessionStateSupport;
    private final LoginStateProbeService loginStateProbeService;
    private final TaskEventWindowReader taskEvents;
    private final com.sixbean.testpike.core.events.domain.component.ExchangeTrailQuery exchangeTrails;
    private final com.sixbean.testpike.core.session.domain.component.LoginStateKeepAliveEnableGate keepAliveEnableGate;

    public LoginStateInternalController(LoginStateService loginStateService,
                                        SessionStateSupport sessionStateSupport,
                                        LoginStateProbeService loginStateProbeService,
                                        TaskEventWindowReader taskEvents,
                                        com.sixbean.testpike.core.events.domain.component.ExchangeTrailQuery exchangeTrails,
                                        com.sixbean.testpike.core.session.domain.component.LoginStateKeepAliveEnableGate keepAliveEnableGate) {
        this.loginStateService = loginStateService;
        this.sessionStateSupport = sessionStateSupport;
        this.loginStateProbeService = loginStateProbeService;
        this.taskEvents = taskEvents;
        this.exchangeTrails = exchangeTrails;
        this.keepAliveEnableGate = keepAliveEnableGate;
    }

    /**
     * Keep-alive enable door: flips the per-row switch only after the
     * synchronous double pre-check passes (design ruling section 5). The
     * pre-check refusals reach callers as the core wording, verbatim.
     */
    @org.springframework.web.bind.annotation.PatchMapping("/{id}/keepalive")
    public Response<Void> setKeepAlive(@PathVariable long id,
                                       @RequestBody com.sixbean.testpike.core.session.api.dto.LoginStateKeepAliveUpdate update) {
        keepAliveEnableGate.apply(id, update.enabled(), update.url(), update.intervalMinutes());
        return Response.builderSuccess(null);
    }

    /**
     * "Test now": replay the stored session against one target origin and
     * echo the whole exchange into the process-event tail (sync run, the
     * caller tails the events afterwards through probe-events). The optional
     * JSON payload carries the request shape (GET/POST/PUT + body template
     * with {{CRED:key}} placeholders); omitted = GET against the resolved
     * address, byte-identical to the historical door.
     */
    @PostMapping("/{id}/probe")
    public Response<ProbeResult> probe(@PathVariable long id,
                                       @RequestParam(required = false) String url,
                                       @RequestBody(required = false)
                                       com.sixbean.testpike.core.session.api.dto.ProbeRunInput run) {
        return Response.builderSuccess(loginStateProbeService.probe(id, url,
                run == null ? null : run.method(), run == null ? null : run.bodyTemplate()));
    }

    /** Event window after cursor for one probe run (tail paging). */
    @GetMapping("/{id}/probe-events")
    public Response<java.util.List<com.sixbean.testpike.core.events.api.dto.TaskEventRow>> probeEvents(
            @PathVariable long id, @RequestParam String runToken,
            @RequestParam(defaultValue = "0") long cursor) {
        return Response.builderSuccess(taskEvents.since(
                LoginStateProbeServiceImpl.EVENT_JOB_KIND, String.valueOf(id), runToken, cursor, null));
    }

    /** Run history of this state's probe trail (revisit entry list, newest
     *  run first; the per-run replay itself stays on probe-events). */
    @GetMapping("/{id}/probe-runs")
    public Response<java.util.List<com.sixbean.testpike.core.events.api.dto.TaskEventRunRow>> probeRuns(
            @PathVariable long id, @RequestParam(required = false) Integer limit) {
        return Response.builderSuccess(taskEvents.runs(
                LoginStateProbeServiceImpl.EVENT_JOB_KIND, String.valueOf(id), limit));
    }

    /** Keep-alive trail for one row (R67 revisit door). Frames land only on
     *  stop transitions; the window/paging plumbing is the SHARED
     *  TaskEventWindowReader single source — only the kind token differs, so
     *  no second replay chain exists. */
    @GetMapping("/{id}/keepalive-events")
    public Response<java.util.List<com.sixbean.testpike.core.events.api.dto.TaskEventRow>> keepaliveEvents(
            @PathVariable long id, @RequestParam String runToken,
            @RequestParam(defaultValue = "0") long cursor) {
        return Response.builderSuccess(taskEvents.since(
                LoginStateKeepAliveRunner.EVENT_JOB_KIND, String.valueOf(id), runToken, cursor, null));
    }

    /** Keep-alive run history of this row (transition frames only, newest run first). */
    @GetMapping("/{id}/keepalive-runs")
    public Response<java.util.List<com.sixbean.testpike.core.events.api.dto.TaskEventRunRow>> keepaliveRuns(
            @PathVariable long id, @RequestParam(required = false) Integer limit) {
        return Response.builderSuccess(taskEvents.runs(
                LoginStateKeepAliveRunner.EVENT_JOB_KIND, String.valueOf(id), limit));
    }

    /**
 * Gated reveal of one sealed header value of one probe trail row
 * ; login + project checks run at the admin bridge, the
 * audit row lands with the query itself).
 */
    @GetMapping("/{id}/probe-events/{eventId}/reveal")
    public Response<com.sixbean.testpike.core.events.domain.component.ExchangeTrailQuery.TrailRevealView>
            revealTrailField(@PathVariable long id, @PathVariable long eventId,
                             @RequestParam String field) {
        return Response.builderSuccess(exchangeTrails.reveal(
                LoginStateProbeServiceImpl.EVENT_JOB_KIND, String.valueOf(id), eventId, field));
    }

    /** Paged full-text window of one probe trail row (same gate + audit). */
    @GetMapping("/{id}/probe-events/{eventId}/full")
    public Response<com.sixbean.testpike.core.events.domain.component.ExchangeTrailQuery.TrailFullTextView>
            trailFullText(@PathVariable long id, @PathVariable long eventId,
                          @RequestParam(defaultValue = "0") int from,
                          @RequestParam(required = false) Integer limit) {
        return Response.builderSuccess(exchangeTrails.fullText(
                LoginStateProbeServiceImpl.EVENT_JOB_KIND, String.valueOf(id), eventId, from, limit));
    }

    /** Register or renew one state (same-name renewal updates, D9; returns row id). */
    @PostMapping
    public Response<Long> register(@RequestParam long projectId,
                                   @RequestBody LoginStateInput input) {
        return Response.builderSuccess(loginStateService.register(projectId, input));
    }

    /**
     * Edit one ledger row in place by id (ledger dialog save door). The row's
     * own project owns the write; a projectId inside the body is ignored, so
     * the admin bridge cannot steer a row into another project.
     */
    @PutMapping("/{id}")
    public Response<Void> update(@PathVariable long id, @RequestBody LoginStateInput input) {
        loginStateService.update(id, input);
        return Response.builderSuccess(null);
    }

    /** One ledger row by id (missing = honest 404 wording from the service). */
    @GetMapping("/{id}")
    public Response<LoginStateInfo> get(@PathVariable long id) {
        return Response.builderSuccess(loginStateService.get(id));
    }

    /** Project segment (enabledOnly=TRUE keeps usable rows, null = all). */
    @GetMapping
    public Response<List<LoginStateInfo>> list(@RequestParam long projectId,
                                               @RequestParam(required = false) Boolean enabledOnly) {
        return Response.builderSuccess(loginStateService.list(projectId, enabledOnly));
    }

    /**
 * Paged ledger segment for the management face (S-group display-scale rule):
 * keyword matches state name or account label, status 1/0/null = enabled /
 * disabled / all, sortBy must be a ledger column (derived columns refused).
 */
    @GetMapping("/page")
    public Response<PageResult<LoginStateInfo>> listPage(
            @RequestParam long projectId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Response.builderSuccess(loginStateService.listPage(projectId, keyword, status,
                sortBy, sortDir == null || !sortDir.equalsIgnoreCase("descending"), page, size));
    }

    /**
     * D9 resolution for consumers: exact row by stateName, or role-scope with
     * fail-closed candidate listing on ambiguity. Returns metadata only —
     * never the vault value.
     */
    @GetMapping("/resolve")
    public Response<LoginStateInfo> resolve(@RequestParam long projectId,
                                            @RequestParam(required = false) String stateName,
                                            @RequestParam(required = false) String roleScope) {
        LoginState row = sessionStateSupport.resolve(projectId, stateName, roleScope);
        return Response.builderSuccess(loginStateService.get(row.getId()));
    }

    /** Enable/disable (disable takes effect from the next session, running work is not cut). */
    @PutMapping("/{id}/enabled")
    public Response<Void> setEnabled(@PathVariable long id, @RequestParam boolean enabled) {
        loginStateService.setEnabled(id, enabled);
        return Response.builderSuccess(null);
    }

    /** Remove the ledger row (vault row stays for audit trail). */
    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable long id) {
        loginStateService.delete(id);
        return Response.builderSuccess(null);
    }
}
