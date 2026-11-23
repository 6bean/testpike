package com.sixbean.testpike.core.aichat.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.aichat.api.dto.ApplyDraftRequest;
import com.sixbean.testpike.core.aichat.api.dto.ApplyGroupDraftRequest;
import com.sixbean.testpike.core.aichat.api.dto.ApplyGroupScopeDraftRequest;
import com.sixbean.testpike.core.aichat.api.dto.CheckedSubsetRequest;
import com.sixbean.testpike.core.aichat.api.dto.DraftApplyResult;
import com.sixbean.testpike.core.aichat.api.dto.DraftGroupApplyResult;
import com.sixbean.testpike.core.aichat.api.dto.DraftTicket;
import com.sixbean.testpike.core.aichat.api.dto.GroupScopeTicketRequest;
import com.sixbean.testpike.core.aichat.api.dto.PendingGroupCardView;
import com.sixbean.testpike.core.aichat.api.dto.SystemDraftInput;
import com.sixbean.testpike.core.aichat.domain.service.DraftConfirmationService;
import com.sixbean.testpike.core.aichat.domain.service.DraftPendingCountService;
import com.sixbean.testpike.core.aichat.domain.service.SystemDraftService;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Internal confirmation door for dialog AI drafts of every kind (AO batch
 * stage 3b , generalized in stage 1; 02
 * POST /internal/ai-chat/drafts/{id}/confirm-token and .../apply).
 * <p>Thin by rule — the guard order (existence → session ownership → pending
 * → ticket → per-kind optimistic anchor → write) lives single-sourced in
 * {@link DraftConfirmationService}; this class only translates the wire.
 * The operator arrives through the standard X-Operator-Id header (same
 * posture as the tool confirm-token door), so the admin lane pins identity
 * before the call and core re-checks ownership against the draft's session.
 */
@RestController
@RequestMapping("/internal/ai-chat/drafts")
public class DraftInternalController {

    private final DraftConfirmationService confirmationService;
    private final SystemDraftService systemDraftService;
    private final DraftPendingCountService pendingCountService;

    public DraftInternalController(DraftConfirmationService confirmationService,
                                   SystemDraftService systemDraftService,
                                   DraftPendingCountService pendingCountService) {
        this.confirmationService = confirmationService;
        this.systemDraftService = systemDraftService;
        this.pendingCountService = pendingCountService;
    }

    /** Open the door for one pending draft (audited; writes nothing). */
    @PostMapping("/{id}/confirm-token")
    public Response<DraftTicket> confirmToken(@PathVariable long id) {
        return Response.builderSuccess(confirmationService.issueConfirmToken(
                OperatorContext.currentOperatorId(), id));
    }

    /**
 * Re-tick which candidate rows a pending card authorises, before the
 * confirm ticket is issued ( piece D3; PATCH
 * /internal/ai-chat/drafts/{id}/checked-subset). Names outside the
 * server-side candidate snapshot refuse closed; values never ride the
 * payload — the landing chain reloads them from the transient slot.
 */
    @PatchMapping("/{id}/checked-subset")
    public Response<Integer> checkedSubset(@PathVariable long id,
                                           @RequestBody CheckedSubsetRequest request) {
        return Response.builderSuccess(confirmationService.updateCheckedSubset(
                OperatorContext.currentOperatorId(), id,
                request == null ? null : request.names()));
    }

    /** Walk through the door: consume the ticket, re-check the anchor, land the proposal. */
    @PostMapping("/{id}/apply")
    public Response<DraftApplyResult> apply(@PathVariable long id,
                                            @RequestBody ApplyDraftRequest request) {
        return Response.builderSuccess(confirmationService.apply(
                OperatorContext.currentOperatorId(), id,
                request == null ? null : request.confirmToken(),
                request == null ? null : request.selectedAction()));
    }

    /**
 * Open the door for a GROUP confirmation (AM batch piece 2; POST
 * /internal/ai-chat/drafts/group/{messageId}/confirm-token). One ticket
 * bound to the answer message; every pending card of that answer walked
 * the same per-row guards core-side before issue.
 */
    @PostMapping("/group/{messageId}/confirm-token")
    public Response<DraftTicket> groupConfirmToken(@PathVariable long messageId) {
        return Response.builderSuccess(confirmationService.issueGroupConfirmToken(
                OperatorContext.currentOperatorId(), messageId));
    }

    /**
     * Walk through the group door: one ticket, the human-checked subset of
     * the answer's cards, one transaction (POST /internal/ai-chat/drafts/
     * group/{messageId}/apply). The body carries only ticket + ids; proposal
     * anchors and payloads never round-trip through the caller.
     */
    @PostMapping("/group/{messageId}/apply")
    public Response<DraftGroupApplyResult> applyGroup(@PathVariable long messageId,
                                                      @RequestBody ApplyGroupDraftRequest request) {
        return Response.builderSuccess(confirmationService.applyGroup(
                OperatorContext.currentOperatorId(), messageId,
                request == null ? null : request.confirmToken(),
                request == null ? null : request.draftIds()));
    }

    /**
 * Scope-shaped group doors : the same
 * issue/apply pair addressed by the DraftGroupScope wire, so a
 * system-origin group (project + object kind, minted by the linkage
 * bus) opens through the identical door machinery. The message-keyed
 * endpoints above stay untouched — the
 * scope pair is an added shape, never a replacement. Guard order,
 * ticket binding and refusal spectrum all live in the service; this
 * class only translates the wire.
 */
    @PostMapping("/group/confirm-token")
    public Response<DraftTicket> groupScopeConfirmToken(
            @RequestBody GroupScopeTicketRequest request) {
        return Response.builderSuccess(confirmationService.issueGroupConfirmTokenByScope(
                OperatorContext.currentOperatorId(),
                request == null ? null : request.groupScope()));
    }

    @PostMapping("/group/apply")
    public Response<DraftGroupApplyResult> applyGroupScope(
            @RequestBody ApplyGroupScopeDraftRequest request) {
        return Response.builderSuccess(confirmationService.applyGroupByScope(
                OperatorContext.currentOperatorId(),
                request == null ? null : request.groupScope(),
                request == null ? null : request.confirmToken(),
                request == null ? null : request.draftIds()));
    }

    /**
 * Read side of the group door (AN batch piece D4; GET
 * /internal/ai-chat/drafts/group/pending?scope=...). Lists the group's
 * pending cards for the panel BEFORE a ticket exists — pure read, the
 * doors above stay the only write path. Empty group = empty list.
 */
    @GetMapping("/group/pending")
    public Response<List<PendingGroupCardView>> groupPending(@RequestParam String scope) {
        return Response.builderSuccess(confirmationService.pendingGroupCards(
                OperatorContext.currentOperatorId(), scope));
    }

    /**
 * File a system-origin proposal as a pending draft , the
 * ai-service distillation channel; POST /internal/ai-chat/drafts/system).
 * Posting never writes the target object — only the human door above
 * does — so the machine can propose, never self-apply.
 */
    @PostMapping("/system")
    public Response<Long> postSystem(@RequestBody SystemDraftInput input) {
        return Response.builderSuccess(systemDraftService.postSystemDraft(input));
    }

    /**
 * How many proposals about one object still wait for a human decision
 * .
 * Pure read — the confirmation doors above stay the only write path.
 */
    @GetMapping("/pending-count")
    public Response<Integer> pendingCount(@RequestParam long projectId,
                                          @RequestParam String objectType,
                                          @RequestParam long targetId) {
        return Response.builderSuccess(
                pendingCountService.countPending(projectId, objectType, targetId));
    }
}
