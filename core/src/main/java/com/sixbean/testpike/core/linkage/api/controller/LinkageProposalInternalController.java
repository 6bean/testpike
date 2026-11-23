package com.sixbean.testpike.core.linkage.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.linkage.api.dto.LinkageProposalInput;
import com.sixbean.testpike.core.linkage.api.dto.LinkageProposalRecord;
import com.sixbean.testpike.core.linkage.api.dto.LinkageProposalWrite;
import com.sixbean.testpike.core.linkage.domain.service.LinkageProposalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Linkage proposal snapshot internal interface ( script repair
 * trigger; core-side store consumed by the AI service's proposal legs and by
 * the grouped-card accept surface; internal exempt from the open API
 * registry — ScriptRepairInternalController shape precedent).
 */
@RestController
@RequestMapping("/internal/linkage-proposals")
public class LinkageProposalInternalController {

    private final LinkageProposalService proposalService;

    public LinkageProposalInternalController(LinkageProposalService proposalService) {
        this.proposalService = proposalService;
    }

    /** Write one snapshot (idempotent by impact_id; consumed rows stay frozen). */
    @PostMapping
    public Response<LinkageProposalWrite> record(@RequestBody LinkageProposalInput input) {
        return Response.builderSuccess(proposalService.record(input));
    }

    /** Read the snapshot of one impact row (null when no draft was posted yet). */
    @GetMapping
    public Response<LinkageProposalRecord> get(@RequestParam long impactId) {
        return Response.builderSuccess(proposalService.get(impactId));
    }
}
