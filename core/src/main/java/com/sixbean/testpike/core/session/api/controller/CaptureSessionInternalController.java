package com.sixbean.testpike.core.session.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.session.api.dto.CaptureCandidateMetadata;
import com.sixbean.testpike.core.session.api.dto.CaptureCommitValues;
import com.sixbean.testpike.core.session.api.dto.CaptureSessionClaim;
import com.sixbean.testpike.core.session.api.dto.CaptureSessionInfo;
import com.sixbean.testpike.core.session.api.dto.CaptureSessionInput;
import com.sixbean.testpike.core.session.api.dto.CaptureSessionReport;
import com.sixbean.testpike.core.session.api.dto.CaptureSlotStageInput;
import com.sixbean.testpike.core.session.domain.service.CaptureSessionService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Capture-session internal endpoints (core service layer, called by admin over
 * HTTP; LoginStateInternalController layering). Authorization and the project
 * data gate run on the admin side; scope validation, the shell switch refusal
 * and the transient lifecycle stay single-sourced in the core service.
 *
 * <p>No value door: the response shape carries no debug endpoint and no
 * captured secret — the endpoint only ever lives inside the Redis session
 * payload (red line 4), and candidate values never leave the executor side
 * before the commit round (red line 3).
 */
@RestController
@RequestMapping("/internal/session-capture-sessions")
public class CaptureSessionInternalController {

    private final CaptureSessionService captureSessionService;

    public CaptureSessionInternalController(CaptureSessionService captureSessionService) {
        this.captureSessionService = captureSessionService;
    }

    /** Open one capture session (returns the transient session id). */
    @PostMapping
    public Response<String> open(@RequestParam long projectId,
                                 @RequestBody CaptureSessionInput input) {
        return Response.builderSuccess(captureSessionService.open(projectId, input));
    }

    /** Session metadata for the wizard (expired = honest expiry wording from the service). */
    @GetMapping("/{sessionId}")
    public Response<CaptureSessionInfo> find(@PathVariable String sessionId) {
        return Response.builderSuccess(captureSessionService.find(sessionId));
    }

    /** Explicit abandon (terminal delete; idempotent). */
    @DeleteMapping("/{sessionId}")
    public Response<Void> abandon(@PathVariable String sessionId) {
        captureSessionService.abandon(sessionId);
        return Response.builderSuccess(null);
    }

    /**
     * Executor hand-off: pop one queued capture session. Answered only on the
     * internal protocol channel (api relays it to the executor) — this is the
     * single shape allowed to carry the debug endpoint, and it is never
     * reachable from the admin/browser side (red line 4).
     */
    @PostMapping("/claim")
    public Response<CaptureSessionClaim> claim(@RequestParam long executorId) {
        return Response.builderSuccess(captureSessionService.claim(executorId));
    }

    /** Executor answer for one round (candidate metadata only). */
    @PostMapping("/{sessionId}/report")
    public Response<Long> report(@PathVariable String sessionId,
                                 @RequestBody CaptureSessionReport report) {
        return Response.builderSuccess(captureSessionService.report(sessionId, report));
    }

    /** Candidate checklist for the wizard (metadata only, no value slot — red line 3). */
    @GetMapping("/{sessionId}/candidates")
    public Response<List<CaptureCandidateMetadata>> candidates(@PathVariable String sessionId) {
        return Response.builderSuccess(captureSessionService.candidates(sessionId));
    }

    /**
 * Wizard confirmation: mark the checked subset and re-queue the commit
 * and the project manage gate run on the admin side.
 */
    @PostMapping("/{sessionId}/commit")
    public Response<Integer> commit(@PathVariable String sessionId,
                                    @RequestBody List<String> selectedNames) {
        return Response.builderSuccess(captureSessionService.commit(sessionId, selectedNames));
    }

    /**
     * Executor answer for the commit round: values transit memory straight
     * into the vault register call inside this JVM hop — the session payload
     * never stores them and no log line carries them (red lines 3/4).
     */
    @PostMapping("/{sessionId}/commit-values")
    public Response<Long> commitValues(@PathVariable String sessionId,
                                       @RequestBody CaptureCommitValues values) {
        return Response.builderSuccess(captureSessionService.commitValues(sessionId, values));
    }

    /**
 * Screenshot staging channel : the ai-service
 * attachment parser pushes freshly parsed rows with their values into the
 * same TTL-bound transient store the wizard sessions live in. The answer
 * is only the staging token — nothing value-bearing ever echoes through
 * this door (piece E red line: values transit memory and Redis only).
 */
    @PostMapping("/stage")
    public Response<String> stage(@RequestBody CaptureSlotStageInput input) {
        return Response.builderSuccess(captureSessionService.stageScreenshotEntries(input));
    }

    /**
 * Name-domain probe for the draft emitter : answers ONLY the
 * staged names of one screenshot slot so every card row can be rewritten
 * onto the server's spelling before a human ever sees it. An absent or
 * expired slot answers an empty list; no value ever crosses this door
 * (piece E red line).
 */
    @GetMapping("/stage/{token}/names")
    public Response<List<String>> stagedNames(@PathVariable String token) {
        return Response.builderSuccess(captureSessionService.stagedNames(token));
    }
}
