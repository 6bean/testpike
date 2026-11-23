package com.sixbean.testpike.core.cluster.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.cluster.api.dto.EnrollAcceptedView;
import com.sixbean.testpike.core.cluster.api.dto.EnrollHeartbeatView;
import com.sixbean.testpike.core.cluster.api.dto.EnrollTokenIssueInput;
import com.sixbean.testpike.core.cluster.api.dto.EnrollTokenIssuedView;
import com.sixbean.testpike.core.cluster.api.dto.EnrollTokenView;
import com.sixbean.testpike.core.cluster.api.dto.InstallKitConfigView;
import com.sixbean.testpike.core.cluster.domain.service.ClusterEnrollTokenService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Internal interface for installation pool-entry tokens in the core service layer, called by the admin bridge and api protocol entry.
 * <p>Permission keys such as perm:cluster:install:run are validated on the admin side, and the audit write surface is hooked to core service.
 * The @Audit annotation is applied, and this controller must not stack a second set, as AppautoCatalogInternalController does.
 * enroll/heartbeat requests are forwarded in through the api /internal surface, and scripts outside the system are forbidden from connecting directly to core as a strict service-layering rule.
 */
@RestController
@RequestMapping("/internal/cluster")
public class ClusterEnrollInternalController {

    private final ClusterEnrollTokenService enrollTokenService;

    public ClusterEnrollInternalController(ClusterEnrollTokenService enrollTokenService) {
        this.enrollTokenService = enrollTokenService;
    }

    /** Issue a one-time pool-entry token, with plaintext appearing only once in this response and the cluster page generating token-bearing commands from it. */
    @PostMapping("/enroll-tokens")
    public Response<EnrollTokenIssuedView> issue(@RequestBody EnrollTokenIssueInput input) {
        return Response.builderSuccess(enrollTokenService.issue(input));
    }

    /** Token list, with the admin surface exposing neither plaintext nor digest. */
    @GetMapping("/enroll-tokens")
    public Response<List<EnrollTokenView>> list() {
        return Response.builderSuccess(enrollTokenService.list());
    }

    /** Revoke pending tokens by withdrawing issued-but-unused commands before scale-down and retaining an audit trail. */
    @PostMapping("/enroll-tokens/{id}/revoke")
    public Response<Void> revoke(@PathVariable Long id) {
        enrollTokenService.revoke(id);
        return Response.builderSuccess(null);
    }

    /** Consume tokens through api protocol entry forwarding, passing plaintext only in the request body and never in query parameters because access logs must not expose plaintext. */
    @PostMapping("/enroll")
    public Response<EnrollAcceptedView> enroll(@RequestBody Map<String, Object> body) {
        return Response.builderSuccess(enrollTokenService.enroll(
                str(body.get("token")), str(body.get("fingerprint"))));
    }

    /** Enroll heartbeat relay (from the api protocol entry): mismatched fingerprint is
 * rejected; trailing step keys project onto the node-card bar (03); trailing
 * components key lands the agent's platform-family report on the component
 * ledger . */
    @PostMapping("/enroll/heartbeat")
    public Response<EnrollHeartbeatView> heartbeat(@RequestBody Map<String, Object> body) {
        Object components = body.get("components");
        return Response.builderSuccess(enrollTokenService.heartbeat(
                str(body.get("token")), str(body.get("fingerprint")),
                str(body.get("step")), str(body.get("stepStatus")), str(body.get("message")),
                components instanceof List<?> list ? list : null));
    }

 /** Read-only installation configuration endpoint (c2), exposing the current global image-source redirection key; if missing, it returns an empty string to indicate the official source. */
    @GetMapping("/installkit-config")
    public Response<InstallKitConfigView> installkitConfig() {
        return Response.builderSuccess(new InstallKitConfigView(enrollTokenService.mirrorBaseUrl()));
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
