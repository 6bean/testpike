package com.sixbean.testpike.core.linkage.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.linkage.LinkageDraftImpact;
import com.sixbean.testpike.common.linkage.LinkagePendingImpacts;
import com.sixbean.testpike.core.linkage.domain.service.LinkageImpactQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


/**
 * Pending downstream-impact query, internal seam ( user-facing card
 * panel; admin bridges it after its own project-view permission guard, so the
 * ledger never trusts a client-supplied projectId — the upstream coordinates
 * are the anchor, same posture as the proposal store's internal interface).
 */
@RestController
@RequestMapping("/internal/linkage-impacts")
public class LinkageImpactInternalController {

    private final LinkageImpactQueryService queryService;

    public LinkageImpactInternalController(LinkageImpactQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * Pending impacts of one upstream object, oldest first; empty = zero
     * impact. The answer carries the owning project resolved from the
     * upstream object itself so the admin bridge can gate on it without
     * ever trusting a caller-supplied projectId.
     */
    @GetMapping("/pending")
    public Response<LinkagePendingImpacts> pending(@RequestParam String upstreamType,
                                                   @RequestParam long upstreamId) {
        return Response.builderSuccess(queryService.findPendingViewByUpstream(upstreamType, upstreamId));
    }

    /**
 * Live ledger anchors for the draft cards of one grouped card (
 * segment D③); the admin bridge adds its project-view gate on the draft
 * rows' own project ids.
 */
    @GetMapping("/by-drafts")
    public Response<List<LinkageDraftImpact>> byDrafts(@RequestParam List<Long> ids) {
        return Response.builderSuccess(queryService.findImpactsByDrafts(ids));
    }
}
