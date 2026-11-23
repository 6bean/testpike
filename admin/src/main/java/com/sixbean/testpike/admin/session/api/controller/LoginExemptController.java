package com.sixbean.testpike.admin.session.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.session.api.dto.LoginExemptDeclareRequest;
import com.sixbean.testpike.admin.session.api.dto.LoginExemptReviewRequest;
import com.sixbean.testpike.admin.session.api.dto.LoginExemptView;
import com.sixbean.testpike.admin.session.infrastructure.remote.client.CoreLoginExemptClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Login-exemption admin face (AV5, LoginStateController idiom). Exactly three
 * doors: read the declaration, declare/overwrite it, record a re-check.
 *
 * <p>The bridge authorizes and forwards only. Form rules, vault cross-checks
 * and the re-check verdict table stay single-sourced in core (red line 6).
 * The ownership lens always comes from the core echo of the environment row,
 * never from the caller; reads demand the view door and both write doors
 * demand the exemption key — a declaration is a guarantee about a test
 * environment, hence the approve-family permission, not a plain edit. There
 * is no fourth door here and none may ever appear: no captcha-solving, no
 * code-reading, no page-side self-attestation (red lines 1 and 3).
 */
@RestController
@RequestMapping("/api/v1/login-exempt")
public class LoginExemptController {

    private final CoreLoginExemptClient coreLoginExemptClient;
    private final ProjectAccessGuard guard;

    public LoginExemptController(CoreLoginExemptClient coreLoginExemptClient,
                                 ProjectAccessGuard guard) {
        this.coreLoginExemptClient = coreLoginExemptClient;
        this.guard = guard;
    }

    /** Current declaration of one environment (view door; undeclared is honest). */
    @GetMapping("/{envId}")
    @RequirePermission("perm:session:read")
    public Response<LoginExemptView> get(@PathVariable long envId) {
        LoginExemptView declaration = coreLoginExemptClient.find(envId);
        guard.assertCanView(UserContext.current(), declaration.projectId());
        return Response.builderSuccess(declaration);
    }

    /** Declare or overwrite the exemption slot (exemption door + manage lens). */
    @PutMapping("/{envId}")
    @RequirePermission("perm:session:exempt")
    public Response<LoginExemptView> declare(@PathVariable long envId,
                                             @RequestBody LoginExemptDeclareRequest request) {
        LoginExemptView declaration = coreLoginExemptClient.find(envId);
        guard.assertCanManage(UserContext.current(), declaration.projectId());
        return Response.builderSuccess(coreLoginExemptClient.declare(envId, request));
    }

    /** Record one re-check observation (exemption door; verdict table runs in core). */
    @PostMapping("/{envId}/review")
    @RequirePermission("perm:session:exempt")
    public Response<LoginExemptView> review(@PathVariable long envId,
                                            @RequestBody LoginExemptReviewRequest request) {
        LoginExemptView declaration = coreLoginExemptClient.find(envId);
        guard.assertCanManage(UserContext.current(), declaration.projectId());
        return Response.builderSuccess(coreLoginExemptClient.recordReview(envId, request));
    }
}
