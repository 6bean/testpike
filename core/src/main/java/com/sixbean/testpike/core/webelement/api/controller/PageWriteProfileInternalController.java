package com.sixbean.testpike.core.webelement.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.webelement.PageWriteProfilePageView;
import com.sixbean.testpike.common.webelement.PageWriteRiskView;
import com.sixbean.testpike.core.webelement.api.dto.CaseDraftFromFailureInput;
import com.sixbean.testpike.core.webelement.domain.service.PageWriteFailureDraftService;
import com.sixbean.testpike.core.webelement.domain.service.PageWriteProfileQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Page write-risk ledger door (internal): the admin bridge mirrors it to the
 * browser screen and the ai-service kernel prompt consumes the same rows
 * before planning a probe. segment shape and caps are core-side; the caller
 * never re-derives risk.
 */
@RestController
@RequestMapping("/internal/page-write-profiles")
public class PageWriteProfileInternalController {

    private final PageWriteProfileQueryService queryService;
    private final PageWriteFailureDraftService draftService;

    public PageWriteProfileInternalController(PageWriteProfileQueryService queryService,
                                              PageWriteFailureDraftService draftService) {
        this.queryService = queryService;
        this.draftService = draftService;
    }

    /** Paged risk pages of one project, newest activity first (S-group worst-scale contract). */
    @GetMapping("/page")
    public Response<PageWriteProfilePageView> page(@RequestParam long projectId,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "50") int pageSize,
                                                   @RequestParam(required = false) String keyword) {
        return Response.builderSuccess(queryService.pageByProject(projectId, page, pageSize, keyword));
    }

    /**
     * Narrow risk door for the explore kernel: the write-page set of one
     * project as pageKey+pageUrl pairs only (bounded by the service-side
     * cap). Pure read — same posture as the paged door.
     */
    @GetMapping("/risk-pages")
    public Response<List<PageWriteRiskView>> riskPages(@RequestParam long projectId) {
        return Response.builderSuccess(queryService.riskPagesForProject(projectId));
    }

    /**
 * File one booked failure as a pending case-write draft and return the
 * draft ledger id. Forwarding only: existence, segment and index-bound
 * refusals come from the converter, and the draft still waits behind
 * the generic human confirmation door before anything is written.
 */
    @PostMapping("/case-draft")
    public Response<Long> caseDraft(@RequestBody CaseDraftFromFailureInput input) {
        return Response.builderSuccess(
                draftService.toCaseDraft(input.projectId(), input.profileId(), input.failureIndex()));
    }
}
