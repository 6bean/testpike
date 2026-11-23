package com.sixbean.testpike.admin.aichat.api.controller;

import com.sixbean.testpike.admin.aichat.api.dto.EvolutionProposalsMirrorView;
import com.sixbean.testpike.admin.aichat.api.dto.EvolutionRollbackMirrorView;
import com.sixbean.testpike.admin.aichat.api.dto.EvolutionSignalsMirrorView;
import com.sixbean.testpike.admin.aichat.api.dto.EvolutionTrendMirrorView;
import com.sixbean.testpike.admin.aichat.app.service.EvolutionProposalAdminService;
import com.sixbean.testpike.admin.aichat.app.service.EvolutionSignalsAdminService;
import com.sixbean.testpike.admin.aichat.app.service.EvolutionTrendAdminService;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only observation door of the self-evolution loop (AF-i phase 2b):
 * exposes the core signal rollup (follow-up rate, thumbs ledger, error turns,
 * tool-failure distribution) to the evolution surfaces inside the platform.
 * <p>Thin by rule — the gate lives in the application service (the cost
 * overview posture, not a new spectrum) and every semantic validation is
 * single-sourced in core. No write path crosses this door; proposal
 * generation lands through the draft-confirmation chain, never from here.
 */
@RestController
@RequestMapping("/api/v1/ai/chat/evolution")
public class AiChatEvolutionController {

    private final EvolutionSignalsAdminService signalsService;
    private final EvolutionProposalAdminService proposalsService;
    private final com.sixbean.testpike.admin.aichat.app.service.EvolutionTrendAdminService trendService;

    public AiChatEvolutionController(EvolutionSignalsAdminService signalsService,
                                     EvolutionProposalAdminService proposalsService,
                                     com.sixbean.testpike.admin.aichat.app.service.EvolutionTrendAdminService trendService) {
        this.signalsService = signalsService;
        this.proposalsService = proposalsService;
        this.trendService = trendService;
    }

    /**
 * Observation rollup for one natural month (month=yyyy-MM).
 * projectId omitted = platform-wide view (ADMIN only); a named segment =
 * that project's dialog signals with the platform tool-failure ledger.
 */
    @GetMapping("/signals")
    @RequirePermission("perm:aichat:use")
    public Response<EvolutionSignalsMirrorView> evolutionSignals(
            @RequestParam String month,
            @RequestParam(required = false) Long projectId) {
        return Response.builderSuccess(
                signalsService.signals(UserContext.current(), month, projectId));
    }

    /**
 * Manual self-evolution proposal run (AF-i phase 2b-ii close-out): fires
 * one month-window generation for a project segment. Write-side twin of the
 * signals door — a project's manager only, operator pinned from the login
 * context, and the generated cards wait as PENDING drafts behind the
 * shared confirmation door (nothing lands from this call).
 */
    @PostMapping("/proposals")
    @RequirePermission("perm:aichat:use")
    public Response<EvolutionProposalsMirrorView> generateProposals(
            @RequestParam String month,
            @RequestParam(required = false) Long projectId) {
        return Response.builderSuccess(
                proposalsService.generate(UserContext.current(), month, projectId));
    }

    /**
     * One-click rollback of a landed proposal card (AF-i phase 3): undoes the
     * prompt version the card landed as by copying its predecessor forward —
     * the prompt domain's own chain, never a parallel one. Thin by rule: the
     * login identity is pinned as the operator here, and every guard (card
     * kind, applied state, session ownership, once-only mark, family chain)
     * runs single-sourced in core, same posture as the draft-confirmation door.
     */
    @PostMapping("/proposals/{draftId}/rollback")
    @RequirePermission("perm:aichat:use")
    public Response<EvolutionRollbackMirrorView> rollbackProposal(@PathVariable long draftId) {
        return Response.builderSuccess(
                proposalsService.rollback(UserContext.current(), draftId));
    }

    /**
 * Before/after trend report of applied proposals (AF-i phase 4): the run
 * window compared with the next natural month through the same observation
 * rollup the run reads. Thin by rule — window completeness, verdicts and
 * the ineffective mark are single-sourced in core; this door only decides
 * who may look (the signals posture: global = ADMIN, segment = its manager).
 */
    @GetMapping("/trend")
    @RequirePermission("perm:aichat:use")
    public Response<EvolutionTrendMirrorView> trend(
            @RequestParam String month,
            @RequestParam(required = false) Long projectId) {
        return Response.builderSuccess(
                trendService.trend(UserContext.current(), month, projectId));
    }
}
