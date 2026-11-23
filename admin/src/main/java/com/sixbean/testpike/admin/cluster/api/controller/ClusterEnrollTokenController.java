package com.sixbean.testpike.admin.cluster.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.cluster.api.dto.FleetEnrollIssueBody;
import com.sixbean.testpike.admin.cluster.api.dto.FleetEnrollTokenIssuedView;
import com.sixbean.testpike.admin.cluster.api.dto.FleetEnrollTokenView;
import com.sixbean.testpike.admin.cluster.infrastructure.remote.client.CoreClusterEnrollTokenClient;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Enrollment-token bridge for the add-pool wizard (T294d). The pool page needs the
 * one-time pairing command the installer runs on a target host; core owns every
 * decision (allow-roles whitelist, TTL, max uses, fingerprint binding) and this
 * bridge forwards verdicts and errors unchanged (red line 6, no second gate here).
 * <p>Permission reuses perm:project:executor-pool (same plane as the fleet read
 * ports, no new seed); the substantive narrow gate is the core-side live config key
 * installkit.allow-roles, whose refusal already names the settings path.
 * <p>actorRole comes from the authenticated session's primary role and is never
 * readable from the request body (FleetEnrollIssueBody has no such field).
 */
@RestController
@RequestMapping("/api/v1/cluster/enroll-tokens")
public class ClusterEnrollTokenController {

    private final CoreClusterEnrollTokenClient coreClusterEnrollTokenClient;

    public ClusterEnrollTokenController(CoreClusterEnrollTokenClient coreClusterEnrollTokenClient) {
        this.coreClusterEnrollTokenClient = coreClusterEnrollTokenClient;
    }

    /** Ledger window for the wizard review step; rows are structurally plaintext-free. */
    @GetMapping
    @RequirePermission("perm:project:executor-pool")
    public Response<List<FleetEnrollTokenView>> list() {
        return Response.builderSuccess(coreClusterEnrollTokenClient.list());
    }

    /** Issue one pairing token; the plaintext appears once in this response only. */
    @PostMapping
    @RequirePermission("perm:project:executor-pool")
    public Response<FleetEnrollTokenIssuedView> issue(@RequestBody FleetEnrollIssueBody body) {
        return Response.builderSuccess(
                coreClusterEnrollTokenClient.issue(body, sessionPrimaryRole()));
    }

    /** Retire an unused or leaked token; core validates the state transition. */
    @PostMapping("/{id}/revoke")
    @RequirePermission("perm:project:executor-pool")
    public Response<Void> revoke(@PathVariable("id") Long id) {
        coreClusterEnrollTokenClient.revoke(id);
        return Response.builderSuccess(null);
    }

    /** Derivation seam kept visible for the bridge contract test (no second caller). */
    String sessionRoleForTest() {
        return sessionPrimaryRole();
    }

    private String sessionPrimaryRole() {
        List<String> roles = UserContext.current().roles();
        return roles == null || roles.isEmpty() ? null : roles.get(0);
    }
}
