package com.sixbean.testpike.core.session.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.session.api.dto.LoginExemptInfo;
import com.sixbean.testpike.core.session.api.dto.LoginExemptInput;
import com.sixbean.testpike.core.session.api.dto.LoginExemptReviewSignal;
import com.sixbean.testpike.core.session.domain.service.LoginExemptService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Login exemption declaration internal endpoints (core service layer, called
 * by admin over HTTP; same layering as LoginStateInternalController).
 *
 * <p>Authorization (perm:session:exempt for writes, perm:session:read for
 * reads) and the project data gate run on the admin side; this controller
 * keeps none of it. The shapes here have no secret field at all — the master
 * code stays in the credential vault behind a cred_key reference (red line
 * 3), so this bridge cannot become an echo path by construction.
 */
@RestController
@RequestMapping("/internal/login-exempt")
public class LoginExemptInternalController {

    private final LoginExemptService loginExemptService;

    public LoginExemptInternalController(LoginExemptService loginExemptService) {
        this.loginExemptService = loginExemptService;
    }

    /** Current declaration view of one environment (honest undeclared state). */
    @GetMapping("/{envId}")
    public Response<LoginExemptInfo> find(@PathVariable long envId) {
        return Response.builderSuccess(loginExemptService.find(envId));
    }

    /** Declare or overwrite the exemption slot (validation single-sourced in core). */
    @PutMapping("/{envId}")
    public Response<LoginExemptInfo> declare(@PathVariable long envId,
                                             @RequestBody LoginExemptInput input) {
        return Response.builderSuccess(loginExemptService.declare(envId, input));
    }

    /**
     * Record one re-check observation on the declaration slot. The signal
     * vocabulary carries no "captcha solved" member (red line 1) and the
     * verdict table is single-sourced in core, so the page cannot attest a
     * pass by itself (red line 6).
     */
    @PostMapping("/{envId}/review")
    public Response<LoginExemptInfo> review(@PathVariable long envId,
                                            @RequestBody LoginExemptReviewSignal signal) {
        return Response.builderSuccess(loginExemptService.recordReview(envId, signal));
    }
}
