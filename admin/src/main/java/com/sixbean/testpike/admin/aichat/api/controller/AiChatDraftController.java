package com.sixbean.testpike.admin.aichat.api.controller;

import com.sixbean.testpike.admin.aichat.api.dto.CheckedSubsetRequest;
import com.sixbean.testpike.admin.aichat.api.dto.DraftApplyReceiptView;
import com.sixbean.testpike.admin.aichat.api.dto.DraftApplyRequest;
import com.sixbean.testpike.admin.aichat.api.dto.DraftGroupApplyReceiptView;
import com.sixbean.testpike.admin.aichat.api.dto.DraftTicketView;
import com.sixbean.testpike.admin.aichat.api.dto.GroupDraftApplyRequest;
import com.sixbean.testpike.admin.aichat.api.dto.GroupScopeApplyRequest;
import com.sixbean.testpike.admin.aichat.api.dto.GroupScopeConfirmRequest;
import com.sixbean.testpike.admin.aichat.api.dto.PendingGroupCardView;
import com.sixbean.testpike.admin.aichat.infrastructure.remote.client.CoreAiChatClient;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * User-facing confirmation door for dialog AI drafts of every kind (AO batch
 * stage 3b , generalized in stage 1; 02
 * POST /api/v1/ai/chat/drafts/{id}/confirm-token and .../apply).
 * <p>Thin by rule: the login identity is pinned here (a body field can never
 * speak for another user), then every guard — draft existence, session
 * ownership, pending state, one-shot ticket, per-kind optimistic anchor —
 * runs single-sourced in core. The apply body carries nothing but the ticket:
 * target anchor and proposal body never round-trip through the browser, so a
 * forged request cannot aim the write at another entity.
 */
@RestController
@RequestMapping("/api/v1/ai/chat/drafts")
public class AiChatDraftController {

    private final CoreAiChatClient coreClient;
    private final ProjectAccessGuard guard;

    public AiChatDraftController(CoreAiChatClient coreClient, ProjectAccessGuard guard) {
        this.coreClient = coreClient;
        this.guard = guard;
    }

    /**
 * Pending-change badge for the pinned-object panel (AM batch ;
 * 02 GET /api/v1/ai/chat/drafts/pending-count). Project membership gates
 * the read exactly like the case-version history door; the count itself
 * is computed single-sourced in core.
 */
    @GetMapping("/pending-count")
    @RequirePermission("perm:aichat:use")
    public Response<Integer> pendingCount(@RequestParam long projectId,
                                          @RequestParam String objectType,
                                          @RequestParam long targetId) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(coreClient.draftPendingCount(projectId, objectType, targetId));
    }

    /** Open the door for one pending draft (audited core-side; writes nothing). */
    @PostMapping("/{draftId}/confirm-token")
    @RequirePermission("perm:aichat:use")
    public Response<DraftTicketView> confirmToken(@PathVariable long draftId) {
        long userId = UserContext.currentUserId();
        return Response.builderSuccess(coreClient.confirmDraft(draftId, userId));
    }

    /** Apply the confirmed draft: ticket consumed and anchor re-checked core-side. */
    @PostMapping("/{draftId}/apply")
    @RequirePermission("perm:aichat:use")
    public Response<DraftApplyReceiptView> apply(@PathVariable long draftId,
                                                 @RequestBody(required = false)
                                                 DraftApplyRequest request) {
        long userId = UserContext.currentUserId();
        return Response.builderSuccess(coreClient.applyDraft(draftId, userId,
                request == null ? null : request.confirmToken(),
                request == null ? null : request.selectedAction()));
    }

    /**
 * Re-tick the authorised candidate subset of a pending login-state card
 * before the confirm ticket is issued ( piece D3; 02
 * PATCH /api/v1/ai/chat/drafts/{id}/checked-subset). Identity is pinned
 * here like the other doors; every guard — existence, session ownership,
 * pending state, names inside the server snapshot — runs single-sourced
 * in core. Only names cross the boundary, values never do.
 */
    @PatchMapping("/{draftId}/checked-subset")
    @RequirePermission("perm:aichat:use")
    public Response<Integer> checkedSubset(@PathVariable long draftId,
                                           @RequestBody(required = false)
                                           CheckedSubsetRequest request) {
        long userId = UserContext.currentUserId();
        return Response.builderSuccess(coreClient.updateCheckedSubset(
                draftId, userId, request == null ? null : request.names()));
    }

    /**
 * Open the confirmation door for a GROUP of drafts minted by one assistant
 * answer (AM batch piece 2b; 02
 * POST /api/v1/ai/chat/drafts/group/{messageId}/confirm-token). Identity
 * is pinned here exactly like the single-card door; every per-card guard
 * runs single-sourced in core before the ticket is issued.
 */
    @PostMapping("/group/{messageId}/confirm-token")
    @RequirePermission("perm:aichat:use")
    public Response<DraftTicketView> groupConfirmToken(@PathVariable long messageId) {
        long userId = UserContext.currentUserId();
        return Response.builderSuccess(coreClient.confirmGroupDrafts(messageId, userId));
    }

    /**
 * Walk through the group door: one ticket, the human-checked subset of the
 * answer's cards, one core-side transaction (AM batch piece 2b; 02
 * POST /api/v1/ai/chat/drafts/group/{messageId}/apply). The receipt
 * answers line by line — landed cards carry their new version, colliding
 * cards are named with both versions and the regenerate exit.
 */
    @PostMapping("/group/{messageId}/apply")
    @RequirePermission("perm:aichat:use")
    public Response<DraftGroupApplyReceiptView> groupApply(
            @PathVariable long messageId,
            @RequestBody(required = false) GroupDraftApplyRequest request) {
        long userId = UserContext.currentUserId();
        return Response.builderSuccess(coreClient.applyGroupDrafts(messageId, userId,
                request == null ? null : request.confirmToken(),
                request == null ? null : request.draftIds()));
    }

    /**
 * Open the confirmation door for a system-origin group (AN batch
 * piece D4-b; 02 POST /api/v1/ai/chat/drafts/group/confirm-token). The
 * linkage bus mints grouped_draft cards per project + object kind; the
 * panel names that group by its two explicit segment coordinates instead of
 * hand-building the wire. Identity is pinned here and the viewer's
 * project segment is gated exactly like the single-card door; every guard
 * (group resolution, one-shot ticket, row ownership) stays in core.
 */
    @PostMapping("/group/confirm-token")
    @RequirePermission("perm:aichat:use")
    public Response<DraftTicketView> groupScopeConfirmToken(
            @RequestBody GroupScopeConfirmRequest request) {
        long userId = UserContext.currentUserId();
        guard.assertCanView(UserContext.current(), request.projectId());
        return Response.builderSuccess(coreClient.confirmGroupDraftsByScope(userId,
                systemScopeWire(request.projectId(), request.objectType())));
    }

    /**
 * Walk through the scope-shaped group door: one ticket, the human-checked
 * subset, one core-side transaction (AN batch piece D4-b; 02
 * POST /api/v1/ai/chat/drafts/group/apply). Receipt shape is the shared
 * group answer line by line — the write-confirm gate is not relaxed.
 */
    @PostMapping("/group/apply")
    @RequirePermission("perm:aichat:use")
    public Response<DraftGroupApplyReceiptView> groupScopeApply(
            @RequestBody GroupScopeApplyRequest request) {
        long userId = UserContext.currentUserId();
        guard.assertCanView(UserContext.current(), request.projectId());
        return Response.builderSuccess(coreClient.applyGroupDraftsByScope(userId,
                systemScopeWire(request.projectId(), request.objectType()),
                request.confirmToken(), request.draftIds()));
    }

    /**
 * Pending cards of one system-origin group, for the pinned panel BEFORE
 * a ticket exists (AN batch piece D4-b; 02
 * GET /api/v1/ai/chat/drafts/group/pending). Pure read — the two doors
 * above stay the only write path; empty group reads as an empty list.
 */
    @GetMapping("/group/pending")
    @RequirePermission("perm:aichat:use")
    public Response<java.util.List<PendingGroupCardView>> groupPending(
            @RequestParam long projectId,
            @RequestParam(required = false) String objectType) {
        long userId = UserContext.currentUserId();
        guard.assertCanView(UserContext.current(), projectId);
        // No kind = enumerate every non-empty system group of the segment
        // (D4d); the wire grammar and the shape refusal stay in core.
        String scopeWire = objectType == null || objectType.isBlank()
                ? "system:" + projectId
                : systemScopeWire(projectId, objectType);
        return Response.builderSuccess(coreClient.groupPendingCards(userId, scopeWire));
    }

    /**
 * Assemble the canonical system-group wire from explicit segment
 * coordinates. The wire grammar and every shape refusal stay
 * single-sourced in core (DraftGroupScope); this bridge never parses and
 * never grows a second validator — a malformed kind travels unchanged
 * and core refuses it loudly before anything moves.
 */
    private static String systemScopeWire(long projectId, String objectType) {
        return "system:" + projectId + ":" + objectType;
    }
}
