package com.sixbean.testpike.core.aichat.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.aichat.api.dto.ChatContextWaterlineView;
import com.sixbean.testpike.core.aichat.api.dto.ChatExportView;
import com.sixbean.testpike.core.aichat.api.dto.ChatRunInfo;
import com.sixbean.testpike.core.aichat.api.dto.ChatSedimentView;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.api.dto.ChatShareInfo;
import com.sixbean.testpike.core.aichat.api.dto.ChatShareSnapshotView;
import com.sixbean.testpike.core.aichat.api.dto.CreateSessionRequest;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionPageView;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionUsagePageView;
import com.sixbean.testpike.core.aichat.api.dto.EvolutionProposalView;
import com.sixbean.testpike.core.aichat.api.dto.EvolutionSignalView;
import com.sixbean.testpike.core.aichat.api.dto.GenerateEvolutionProposalsRequest;
import com.sixbean.testpike.core.aichat.api.dto.RollbackEvolutionProposalRequest;
import com.sixbean.testpike.core.aichat.api.dto.RenameSessionRequest;
import com.sixbean.testpike.core.aichat.api.dto.StartRunRequest;
import com.sixbean.testpike.core.aichat.domain.service.ChatRunService;
import com.sixbean.testpike.core.aichat.domain.service.ChatSedimentService;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionExportService;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionService;
import com.sixbean.testpike.core.aichat.domain.service.UndoCaseDraftService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Internal dialog-session door : the admin SSE
 * orchestration creates conversations and starts runs through this door
 * before it attaches to the frame ledger.
 * <p>Thin by rule like {@link ChatFrameInternalController}: session validation
 * (title/source bounds, context whitelist) lives in ChatSessionService, the
 * run-opening transaction and after-commit dispatch live in ChatRunService —
 * no state machine and no second validation copy here.
 */
@RestController
@RequestMapping("/internal/ai-chat")
public class ChatSessionInternalController {

    private final ChatSessionService sessionService;
    private final ChatRunService runService;
    private final ChatSessionExportService exportService;
    private final ChatSedimentService sedimentService;
    private final com.sixbean.testpike.core.aichat.domain.service.ChatShareService shareService;
    private final com.sixbean.testpike.core.aichat.domain.service.ChatUsageAttributionService usageAttributionService;
    private final com.sixbean.testpike.core.aichat.domain.service.EvolutionSignalService evolutionSignalService;
    private final com.sixbean.testpike.core.aichat.domain.service.EvolutionProposalService evolutionProposalService;
    private final com.sixbean.testpike.core.aichat.domain.service.EvolutionTrendService evolutionTrendService;
    private final com.sixbean.testpike.core.aichat.domain.service.ChatContextWaterlineService waterlineService;
    private final UndoCaseDraftService undoCaseDraftService;

    public ChatSessionInternalController(ChatSessionService sessionService,
                                         ChatRunService runService,
                                         ChatSessionExportService exportService,
                                         ChatSedimentService sedimentService,
                                         com.sixbean.testpike.core.aichat.domain.service.ChatUsageAttributionService usageAttributionService,
                                         com.sixbean.testpike.core.aichat.domain.service.EvolutionSignalService evolutionSignalService,
                                         com.sixbean.testpike.core.aichat.domain.service.EvolutionProposalService evolutionProposalService,
                                         com.sixbean.testpike.core.aichat.domain.service.EvolutionTrendService evolutionTrendService,
                                         com.sixbean.testpike.core.aichat.domain.service.ChatShareService shareService,
                                         com.sixbean.testpike.core.aichat.domain.service.ChatContextWaterlineService waterlineService,
                                         UndoCaseDraftService undoCaseDraftService) {
        this.sessionService = sessionService;
        this.runService = runService;
        this.exportService = exportService;
        this.sedimentService = sedimentService;
        this.shareService = shareService;
        this.usageAttributionService = usageAttributionService;
        this.evolutionSignalService = evolutionSignalService;
        this.evolutionProposalService = evolutionProposalService;
        this.evolutionTrendService = evolutionTrendService;
        this.waterlineService = waterlineService;
        this.undoCaseDraftService = undoCaseDraftService;
    }

    /** Open a dialog session for a user inside a project segment. */
    @PostMapping("/sessions")
    public Response<ChatSessionInfo> createSession(@RequestBody CreateSessionRequest request) {
        return Response.builderSuccess(sessionService.create(request.projectId(), request.userId(),
                request.title(), request.source(), request.contextJson()));
    }

    /**
 * Paged history list for one user : the admin bridge pins the owner
 * from the login context before calling — this door scopes by the userId it
 * is handed and never guesses. Filters are all optional narrowing.
 */
    @GetMapping("/sessions")
    public Response<ChatSessionPageView> listSessions(@RequestParam long userId,
                                                      @RequestParam(required = false) List<Long> projectIds,
                                                      @RequestParam(required = false) Integer status,
                                                      @RequestParam(required = false) Integer source,
                                                      @RequestParam(required = false) String keyword,
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "20") int pageSize) {
        return Response.builderSuccess(sessionService.listVisible(userId, projectIds, status,
                source, keyword, page, pageSize));
    }

    /**
 * Paged session-attribution rows for the cost dashboard :
 * per-conversation window tokens recomputed from the message ledger,
 * ordered heaviest-first. Read-only and month-scoped; the admin door pins
 * the same permission posture as /cost/overview (global = ADMIN, segment =
 * project owner) before reaching this call.
 */
    @GetMapping("/sessions/usage-attribution")
    public Response<ChatSessionUsagePageView> usageAttribution(
            @RequestParam String month,
            @RequestParam(required = false) Long projectId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Response.builderSuccess(
                usageAttributionService.usageAttribution(month, projectId, page, pageSize));
    }

    /**
     * Observation-signal rollup for the self-evolution loop (AF-i phase 2):
     * follow-up rate, thumbs/error ledger and the tool-failure distribution
     * for one natural month. Read-only; the dialog signals are project-scoped
     * (null = all), the invocation ledger is platform-wide. The proposal
     * generators and the trend report consume this door — no new observation
     * table exists behind it.
     */
    @GetMapping("/evolution-signals")
    public Response<EvolutionSignalView> evolutionSignals(
            @RequestParam String month,
            @RequestParam(required = false) Long projectId) {
        return Response.builderSuccess(evolutionSignalService.observe(month, projectId));
    }

    /**
     * Fire one manual self-evolution proposal run (AF-i phase 2b-ii): reads
     * the month rollup and lands up to three pending draft cards (prompt
     * template / grounding document / unregistered failing tool) on an
     * evolution session owned by the pinned operator. Nothing publishes or
     * auto-lands — confirmation runs through the shared draft door. Manual
     * trigger only: the admin door pins the manager permission and the login
     * operator; the missing-operator shape is refused here fail-closed.
     */
    @PostMapping("/evolution-proposals")
    public Response<EvolutionProposalView> generateEvolutionProposals(
            @RequestBody GenerateEvolutionProposalsRequest request) {
        if (request.operatorId() == null) {
            throw new com.sixbean.testpike.common.exception.ServiceException(
                    "无法确定提案触动人，请重新登录后再试",
                    com.sixbean.testpike.common.exception.BizErrorCode.VALIDATION_ERROR);
        }
        return Response.builderSuccess(evolutionProposalService.generate(
                request.month(), request.projectId(), request.operatorId()));
    }

    /**
     * One-click rollback of a landed proposal card (AF-i phase 3): undoes the
     * prompt version the card landed as by copying its predecessor forward
     * through the prompt domain's own version chain. Every guard (card kind,
     * applied state, session ownership, once-only mark, family chain) runs
     * single-sourced in the proposal service — this door only refuses the
     * missing-operator shape fail-closed, same posture as the run door. The
     * audit row pins who, which run and which versions moved.
     */
    @PostMapping("/evolution-proposals/{draftId}/rollback")
    public Response<com.sixbean.testpike.core.prompt.api.dto.PromptTemplateItem>
            rollbackEvolutionProposal(@PathVariable long draftId,
                                      @RequestBody
                                      RollbackEvolutionProposalRequest request) {
        if (request.operatorId() == null) {
            throw new com.sixbean.testpike.common.exception.ServiceException(
                    "无法确定回滚操作人，请重新登录后再试",
                    com.sixbean.testpike.common.exception.BizErrorCode.VALIDATION_ERROR);
        }
        return Response.builderSuccess(
                evolutionProposalService.rollback(draftId, request.operatorId()));
    }

    /**
     * Before/after trend report of applied proposals (AF-i phase 4): compares
     * the run window's rollup with the next natural month through the SAME
     * observation door the run reads — no parallel metric spec. A completed
     * comparison month judges each applied prompt card (improved / ineffective
     * / insufficient) and stamps the outcome on the draft row so nothing
     * piles up silently; an unfinished month stays "watching" and writes
     * nothing. Read + mark only: this door never lands, rolls back or
     * publishes.
     */
    @GetMapping("/evolution-trend")
    public Response<com.sixbean.testpike.core.aichat.api.dto.EvolutionTrendView> evolutionTrend(
            @RequestParam String month,
            @RequestParam(required = false) Long projectId) {
        return Response.builderSuccess(evolutionTrendService.trend(month, projectId));
    }

    /** Rename one owned session ; ownership + title bounds stay service-side. */
    @PatchMapping("/sessions/{sessionId}")
    public Response<ChatSessionInfo> renameSession(@PathVariable long sessionId,
                                                   @RequestBody RenameSessionRequest request) {
        return Response.builderSuccess(sessionService.rename(sessionId, request.userId(), request.title()));
    }

    /** Archive one owned session : status flips, history keeps — never deletes. */
    @PostMapping("/sessions/{sessionId}/archive")
    public Response<ChatSessionInfo> archiveSession(@PathVariable long sessionId,
                                                    @RequestParam long userId) {
        return Response.builderSuccess(sessionService.archive(sessionId, userId));
    }

    /** Start a run: user echo + assistant placeholder land, then the broker is told to stream. */
    @PostMapping("/sessions/{sessionId}/runs")
    public Response<ChatRunInfo> startRun(@PathVariable long sessionId,
                                          @RequestBody StartRunRequest request) {
        return Response.builderSuccess(runService.openRun(sessionId, request.userId(),
                request.prompt(), request.refObjects(), request.attachments(),
                request.systemManager(), request.questionContext()));
    }

    /**
 * Accumulated session token ledger : the dialog worker reads
 * this once per turn as its budget gate — the same number the E9 cost view
 * shows, so the refusal threshold and the reported consumption can never
 * drift apart. Ownership stays the admin bridge's job upstream (the same
 * posture as {@code /last-seq}: an internal cursor door, not a user door).
 */
    @GetMapping("/sessions/{sessionId}/total-tokens")
    public Response<Long> totalTokens(@PathVariable long sessionId) {
        return Response.builderSuccess(sessionService.findById(sessionId).totalTokens());
    }

    /**
 * Pinned context of one owned session : the dialog
 * worker reads back the whitelisted navigation context stored at create
 * time so anchor facts ride every turn's request. Ownership stays
 * single-sourced in ChatSessionService.requireOwned (AF-g posture) —
 * this door is a translation point with no second validation copy.
 */
    @GetMapping("/sessions/{sessionId}/context")
    public Response<String> sessionContext(@PathVariable long sessionId,
                                           @RequestParam long userId) {
        return Response.builderSuccess(sessionService.requireOwned(sessionId, userId).contextJson());
    }

    /**
 * Context waterline of one owned session : the panel bar's
 * numbers — budget use, folded turns, pinned facts — computed
 * server-side from the sealed ledger and the K8 config. The ownership
 * refusal stays single-sourced in the service (AF-g posture), so a
 * stranger is refused before any read happens.
 */
    @GetMapping("/sessions/{sessionId}/context-waterline")
    public Response<ChatContextWaterlineView> contextWaterline(@PathVariable long sessionId,
                                                               @RequestParam long userId) {
        return Response.builderSuccess(waterlineService.waterline(sessionId, userId));
    }

    /**
 * Markdown export of one session : the core door owns the
 * check — {@code userId} arrives pinned from the admin login context and
 * ChatSessionExportService refuses a stranger by name, so this stays a
 * translation point with no second ownership copy.
 */
    @GetMapping("/sessions/{sessionId}/export")
    public Response<ChatExportView> exportMarkdown(@PathVariable long sessionId,
                                                   @RequestParam long userId) {
        return Response.builderSuccess(exportService.renderMarkdown(userId, sessionId));
    }

    /**
 * Save one owned session's dialog into the knowledge base :
 * same door posture as the export — the owner id is pinned from the admin
 * login context, ownership/empty/replay refusals are single-sourced in
 * ChatSedimentService. Human-triggered upstream; nothing fires this on its
 * own.
 */
    @PostMapping("/sessions/{sessionId}/sediment")
    public Response<ChatSedimentView> sediment(@PathVariable long sessionId,
                                               @RequestParam long userId) {
        return Response.builderSuccess(sedimentService.landAsKnowledge(userId, sessionId));
    }

    /**
 * One-key undo of the session's last accepted case write (AM batch
 * : ownership is re-checked here through the single-sourced
 * {@code requireOwned} (AF-g posture) BEFORE the undo service is touched,
 * then the door files a fresh CASE_ROLLBACK draft card and answers with
 * its ledger id. The card is the deliverable — the human confirmation
 * door stays the only thing that moves business rows, so this endpoint
 * itself never rolls anything back.
 */
    @PostMapping("/sessions/{sessionId}/undo")
    public Response<Long> undoSession(@PathVariable long sessionId,
                                      @RequestParam long userId) {
        sessionService.requireOwned(sessionId, userId);
        return Response.builderSuccess(undoCaseDraftService.undoLastAcceptedWrite(sessionId));
    }

    // ---- dialog snapshot shares ----
    // Same translation-point posture as the export door: the owner id arrives
    // pinned from the admin login context and every judgment (ownership,
    // empty dialog, ttl clamp, anti-enumeration 404) is single-sourced in
    // ChatShareService.

    /** Freeze one owned session into a share snapshot with the requested ttl. */
    @PostMapping("/sessions/{sessionId}/share")
    public Response<ChatShareInfo> createShare(@PathVariable long sessionId,
                                               @RequestParam long userId,
                                               @RequestParam int ttlHours) {
        return Response.builderSuccess(shareService.create(userId, sessionId, ttlHours));
    }

    /** The owner's live share list for one session (revoked rows stay visible). */
    @GetMapping("/sessions/{sessionId}/shares")
    public Response<List<ChatShareInfo>> listShares(@PathVariable long sessionId,
                                                    @RequestParam long userId) {
        return Response.builderSuccess(shareService.listForOwner(userId, sessionId));
    }

    /** Owner-side kill switch, idempotent. */
    @PostMapping("/shares/{shareId}/revoke")
    public Response<ChatShareInfo> revokeShare(@PathVariable long shareId,
                                               @RequestParam long userId) {
        return Response.builderSuccess(shareService.revoke(userId, shareId));
    }

    /**
     * Public-door read: the api service forwards the token here and the
     * frozen copy rides back — no user id on this leg by design (the token
     * itself is the credential, CI badge posture).
     */
    @GetMapping("/shares/{shareToken}")
    public Response<ChatShareSnapshotView> openShare(@PathVariable String shareToken) {
        return Response.builderSuccess(shareService.openByToken(shareToken));
    }
}
