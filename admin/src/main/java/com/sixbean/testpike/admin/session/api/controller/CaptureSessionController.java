package com.sixbean.testpike.admin.session.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.session.api.dto.CaptureCandidateView;
import com.sixbean.testpike.admin.session.api.dto.CaptureSessionStartRequest;
import com.sixbean.testpike.admin.session.api.dto.CaptureSessionView;
import com.sixbean.testpike.admin.session.infrastructure.remote.client.CoreCaptureSessionClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Capture-session wizard admin face .
 *
 * <p>Two doors per call, following the LoginStateController idiom: the
 * capture permission (perm:session:capture — the same door that later registers
 * the committed states) plus the project data gate — manage for open/abandon
 * (a session ends in ledger writes), view for polling. Ownership of
 * id-addressed calls is taken from the core echo, never from the caller.
 * Scope validation, the shell-switch refusal and the expiry wording stay
 * single-sourced in the core service; this bridge only authorizes and forwards.
 */
@RestController
@RequestMapping("/api/v1/session-states/capture-sessions")
public class CaptureSessionController {

    private final CoreCaptureSessionClient coreCaptureSessionClient;
    private final ProjectAccessGuard guard;

    public CaptureSessionController(CoreCaptureSessionClient coreCaptureSessionClient,
                                    ProjectAccessGuard guard) {
        this.coreCaptureSessionClient = coreCaptureSessionClient;
        this.guard = guard;
    }

    /** Open one capture session (capture door + project manage gate). */
    @PostMapping
    @RequirePermission("perm:session:capture")
    public Response<String> open(@RequestBody CaptureSessionStartRequest request) {
        guard.assertCanManage(UserContext.current(), request.projectId());
        return Response.builderSuccess(coreCaptureSessionClient.openSession(request));
    }

    /** Poll session metadata for the wizard (core expiry wording propagates). */
    @GetMapping("/{sessionId}")
    @RequirePermission("perm:session:capture")
    public Response<CaptureSessionView> find(@PathVariable String sessionId) {
        CaptureSessionView session = coreCaptureSessionClient.findSession(sessionId);
        guard.assertCanView(UserContext.current(), session.projectId());
        return Response.builderSuccess(session);
    }

    /** Explicit abandon from the wizard (capture door + manage gate via core echo). */
    @DeleteMapping("/{sessionId}")
    @RequirePermission("perm:session:capture")
    public Response<Void> abandon(@PathVariable String sessionId) {
        CaptureSessionView session = coreCaptureSessionClient.findSession(sessionId);
        guard.assertCanManage(UserContext.current(), session.projectId());
        coreCaptureSessionClient.abandonSession(sessionId);
        return Response.builderSuccess(null);
    }

    /**
     * Candidate checklist for the wizard commit step (capture door + view gate
     * via core echo). Metadata only — no value slot exists anywhere on this
     * face (red line 3: values travel executor-to-core, never to a screen).
     */
    @GetMapping("/{sessionId}/candidates")
    @RequirePermission("perm:session:capture")
    public Response<List<CaptureCandidateView>> candidates(@PathVariable String sessionId) {
        CaptureSessionView session = coreCaptureSessionClient.findSession(sessionId);
        guard.assertCanView(UserContext.current(), session.projectId());
        return Response.builderSuccess(coreCaptureSessionClient.findCandidates(sessionId));
    }

    /**
     * Wizard commit confirmation (capture door + manage gate — the checked
     * subset ends in ledger writes). Ownership is taken from the core echo,
     * never from the caller; the checked/intersection re-filter and the
     * commit signal re-queue are single-sourced in the core service.
     */
    @PostMapping("/{sessionId}/commit")
    @RequirePermission("perm:session:capture")
    public Response<Integer> commit(@PathVariable String sessionId,
                                    @RequestBody List<String> selectedNames) {
        CaptureSessionView session = coreCaptureSessionClient.findSession(sessionId);
        guard.assertCanManage(UserContext.current(), session.projectId());
        return Response.builderSuccess(
                coreCaptureSessionClient.commitSession(sessionId, selectedNames));
    }
}
