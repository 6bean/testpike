package com.sixbean.testpike.core.cluster.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseCandidateDismissInput;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseCandidateInfo;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseManifestInfo;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseRegisterInput;
import com.sixbean.testpike.core.cluster.domain.service.ReleaseCandidateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Release candidate internal endpoints (core service layer; consumed by the
 * admin upgrade-center bridge, pack C).
 * <p>Permission keys are enforced on the admin side (read=
 * perm:cluster:release:candidate:view, confirm=perm:cluster:release:candidate:
 * confirm); this controller is thin — wrap only, zero business semantics
 * (ReleaseManifestInternalController same-domain posture). Validation single
 * point = ReleaseCandidateService (no second check allowed, red line 6).
 */
@RestController
@RequestMapping("/internal/cluster/release-candidate")
public class ReleaseCandidateInternalController {

    private final ReleaseCandidateService candidateService;

    public ReleaseCandidateInternalController(ReleaseCandidateService candidateService) {
        this.candidateService = candidateService;
    }

    /** Pending candidates (status=detected, version-descending = card order). */
    @GetMapping("/list")
    public Response<List<ReleaseCandidateInfo>> list() {
        return Response.builderSuccess(candidateService.listPending());
    }

    /** Pending count (menu badge metric). */
    @GetMapping("/pending-count")
    public Response<Integer> pendingCount() {
        return Response.builderSuccess(candidateService.pendingCount());
    }

    /** Confirm = publish through the register validation single point (version
     * mismatch / disposed rows rejected in plain words by core). */
    @PostMapping("/{id}/confirm")
    public Response<ReleaseManifestInfo> confirm(@PathVariable long id,
                                                 @RequestBody ReleaseRegisterInput input) {
        return Response.builderSuccess(candidateService.confirm(id, input));
    }

    /** Dismiss a candidate (reason required, kept in the payload trail). */
    @PostMapping("/{id}/dismiss")
    public Response<ReleaseCandidateInfo> dismiss(@PathVariable long id,
                                                  @RequestBody ReleaseCandidateDismissInput input) {
        return Response.builderSuccess(candidateService.dismiss(id, input.reason()));
    }
}
