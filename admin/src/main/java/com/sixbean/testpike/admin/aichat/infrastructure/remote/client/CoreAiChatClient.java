package com.sixbean.testpike.admin.aichat.infrastructure.remote.client;

import com.sixbean.testpike.admin.aichat.api.dto.ChatExportMirrorView;
import com.sixbean.testpike.admin.aichat.api.dto.ChatSedimentMirrorView;
import com.sixbean.testpike.admin.aichat.api.dto.ChatFrameView;
import com.sixbean.testpike.admin.aichat.api.dto.ChatInjectLanding;
import com.sixbean.testpike.admin.aichat.api.dto.ChatMessageView;
import com.sixbean.testpike.admin.aichat.api.dto.ChatSessionPageMirrorView;
import com.sixbean.testpike.admin.aichat.api.dto.ChatSessionUsagePageMirrorView;
import com.sixbean.testpike.admin.aichat.api.dto.ChatSessionView;
import com.sixbean.testpike.admin.aichat.api.dto.DraftApplyReceiptView;
import com.sixbean.testpike.admin.aichat.api.dto.DraftTicketView;
import com.sixbean.testpike.common.aichat.ChatImageAttachment;
import com.sixbean.testpike.common.aichat.ChatObjectRef;

import java.util.List;

/**
 * core dialog-door client : the admin SSE
 * orchestration's only window onto the frame ledger and the run door.
 * <p>Kept as an interface so the pump spectra fake it in memory at L2 (test
 * seam); the live chain is proven by L3 against the real core door.
 */
public interface CoreAiChatClient {

    /**
 * Open a dialog session through the core session door (AO batch stage
 * 2b). The caller has already pinned identity and passed the H1 project
 * guard; core keeps the single-point validation of title/source/context.
 *
 * @param source 1 dock / 2 standalone page / 3 badge deep-link
 * @return the created session view (its id keys every later stream call)
 */
    ChatSessionView createSession(long projectId, long userId, String title, int source,
                                  String contextJson);

    /**
 * Ask core to open a run (user echo + assistant placeholder + broker dispatch).
 *
 * @param systemManager vertical verdict from the admin session face, relayed
 * for the grounding trim map
 * @param questionContext the page anchor captured at send time (BE batch
 * ; admin mirrors the wire shape only —
 * core's whitelist door is the single validation point
 * @return the assistant message id — the frame block this run writes into,
 * which the pump can pull whole even after the worker sealed it.
 */
    long startRun(long sessionId, long userId, String prompt, List<ChatObjectRef> refObjects,
                  List<ChatImageAttachment> attachments, boolean systemManager,
                  String questionContext);

    /** Frames with seq strictly greater than the cursor, ownership-checked by userId. */
    List<ChatFrameView> replay(long sessionId, long userId, long cursor);

    /**
     * Frames of one run block after the cursor, regardless of the message status:
     * the safety net for a run the worker sealed before the pump's first poll
     * (instant refusal, fast answer) — the ledger stays authoritative and the
     * pump never degrades such a turn into a fake timeout.
     */
    List<ChatFrameView> replayRun(long userId, long messageId, long cursor);

    /** Relay an interrupt to the ledger door; returns the sealed message view. */
    ChatMessageView abort(long messageId);

    /**
 * Hand the user's mid-turn supplement to the core steering door .
 * The landing is core's verdict only: non-null means the text joined the
 * streaming message at the returned seq, null means no turn is running and
 * the caller must send the text through the regular message channel. This
 * bridge carries identity and judges nothing — session ownership is
 * re-checked core-side like every other message path.
 */
    ChatInjectLanding injectUser(long sessionId, long userId, String content);

    /**
     * Land one turn's 👍/👎 observation signal (self-evolution loop intake):
     * the owner id is pinned from the login context by the admin door and
     * core re-checks session ownership — the bridge carries identity and the
     * optional complaint sentence across the boundary and judges nothing.
     */
    ChatMessageView recordFeedback(long messageId, long userId, int feedback, String reason);

    /**
     * Sealed turns of one session, oldest first (page-reload history): the core
     * door re-checks ownership against {@code userId} and rebuilds streamed
     * answers from the delta frames, so this bridge never re-maps the ledger.
     */
    List<ChatMessageView> listHistory(long sessionId, long userId);

    /**
     * The unsealed streaming turn of one session (reload-resume signal): the
     * core door answers with the streaming message view or an honest null —
     * this bridge carries identity across the boundary and judges nothing,
     * ownership stays single-sourced in core.
     */
    ChatMessageView activeMessage(long sessionId, long userId);

    /**
 * Paged dialog history for one user . {@code projectIds} is
 * the caller's visible-domain intersection resolved admin-side — core binds
 * the owner filter structurally on top, so a foreign conversation is
 * unreachable no matter what this list says.
 */
    ChatSessionPageMirrorView listSessions(long userId, List<Long> projectIds, Integer status,
                                           Integer source, String keyword, int page, int pageSize);

    /** Rename one owned session ; core re-checks ownership against {@code userId}. */
    ChatSessionView renameSession(long sessionId, long userId, String title);

    /**
 * Paged session-attribution rows for the cost dashboard (E9, phase
 * 3): read-only cross-user aggregation, so no owner binding applies here —
 * the door that exposes it keeps the /cost/overview permission posture
 * (global = ADMIN, project segment = its manager).
 */
    ChatSessionUsagePageMirrorView usageAttribution(String month, Long projectId,
                                                    int page, int pageSize);

    /**
 * Observation-signal rollup for the self-evolution loop (AF-i phase 2b):
 * read-only aggregation recomputed core-side from existing ledgers, so no
 * owner binding applies — the admin door keeps the cost-overview permission
 * posture (global = ADMIN, project segment = its manager) and this bridge
 * carries the query untouched (month shape is validated fail-closed by core).
 */
    com.sixbean.testpike.admin.aichat.api.dto.EvolutionSignalsMirrorView evolutionSignals(
            String month, Long projectId);

    /**
     * Before/after trend report of applied proposals (AF-i phase 4): the
     * before/after comparison, the window completeness and the ineffective mark
     * all happen core-side through the same observation rollup the run reads —
     * this bridge pins nothing but the viewer permission (same posture as the
     * signals door) and carries the query untouched.
     */
    com.sixbean.testpike.admin.aichat.api.dto.EvolutionTrendMirrorView evolutionTrend(
            String month, Long projectId);

    /**
     * Fire one manual self-evolution proposal run (AF-i phase 2b-ii): core
     * reads the month rollup and lands pending draft cards on an evolution
     * session owned by {@code operatorId}. The admin door pins the manager
     * permission and the login-pinned operator before calling; every semantic
     * rule (month shape, stale-batch exclusion, negative gate) is validated
     * fail-closed core-side — nothing auto-lands or auto-publishes here.
     */
    com.sixbean.testpike.admin.aichat.api.dto.EvolutionProposalsMirrorView generateEvolutionProposals(
            String month, Long projectId, long operatorId);

    /**
     * One-click rollback of a landed self-evolution proposal card (AF-i
     * phase 3): core walks the full guard chain (card exists, EVOLUTION_PROPOSAL,
     * owned by {@code operatorId}, applied, never rolled back, prompt family
     * with a version chain) and copies the replaced version forward through
     * the prompt domain's existing chain — the bridge pins the login identity
     * only and judges nothing else (ownership single-sourced core-side, same
     * posture as the draft-apply door).
     */
    com.sixbean.testpike.admin.aichat.api.dto.EvolutionRollbackMirrorView
            rollbackEvolutionProposal(long draftId, long operatorId);

    /** Archive one owned session : keeps the history, flips the status; idempotent. */
    ChatSessionView archiveSession(long sessionId, long userId);

    /**
 * Markdown export of one owned session : core re-checks
 * ownership against {@code userId} and ships the text already masked — the
 * bridge never post-processes content (one masking source, the ledger side).
 */
    ChatExportMirrorView exportSession(long sessionId, long userId);

    /**
 * Read one session's context waterline through the core door ( segment
 * 1): budget use, folded turns and pinned facts as the sealed ledger
 * reports them; core refuses a stranger before any read happens.
 */
    com.sixbean.testpike.admin.aichat.api.dto.ChatContextWaterlineMirrorView contextWaterline(
            long sessionId, long userId);

    /**
 * Save one owned session's dialog into the knowledge base (AO batch
 * phase 2b): core owns the landing (export render + document upload +
 * ingest queue) and every refusal — ownership, empty dialog, same-minute
 * replay. The bridge only carries the landed document's identity back.
 */
    ChatSedimentMirrorView sedimentSession(long sessionId, long userId);

    /**
 * File the one-key undo card for one owned session (AM batch segment
 * 3c): core re-checks ownership against {@code userId} and the undo
 * service's own refusals come back verbatim — the bridge carries identity
 * in and the new pending card's ledger id out, judging nothing itself.
 * No business row moves until the human walks the confirmation door.
 */
    long undoSession(long sessionId, long userId);

    /** K8 hot-read of an integer config; unreadable or missing → defaultValue. */
    int readConfigInt(String key, int defaultValue);

    /**
     * K8 hot-read of a raw string config (comma-list shapes the integer reader
     * cannot carry); unreadable or missing → defaultValue.
     */
    String readConfigString(String key, String defaultValue);

    /**
 * Open the confirmation door for one pending draft (AO batch stage 3b
 * . The draft id alone cannot aim anything: core re-checks session
 * ownership against {@code userId} before minting the ticket.
 */
    DraftTicketView confirmDraft(long draftId, long userId);

    /**
     * Apply a confirmed draft: the one-shot ticket is consumed core-side
     * and the base_version re-check runs there too — this bridge only carries
     * identity and the ticket across the boundary.
     */
    DraftApplyReceiptView applyDraft(long draftId, long userId, String confirmToken,
                                      String selectedAction);

    /**
 * Re-tick which candidate rows a pending login-state card authorises,
 * before the confirm ticket is issued ( piece D3; core PATCH
 * /internal/ai-chat/drafts/{id}/checked-subset). Names only — the values
 * never ride this call; core refuses the whole edit when a name falls
 * outside the server-side snapshot and no-ops an unchanged list (R28).
 * The answer is the number of rows left ticked.
 */
    int updateCheckedSubset(long draftId, long userId, List<String> names);

    /**
 * Count undecided drafts (pending + stale) aimed at one object inside
 * the project segment (AM batch ; core GET
 * /internal/ai-chat/drafts/pending-count). Pure read for the pinned-
 * object panel's badge; the membership gate runs admin-side before the
 * bridge is crossed.
 */
    int draftPendingCount(long projectId, String objectType, long targetId);

    /**
 * Open the confirmation door for a GROUP of drafts minted by one assistant
 * answer .
 * One ticket bound to the message id; core walks every pending card of
 * that answer through the same per-row guards before issuing.
 */
    DraftTicketView confirmGroupDrafts(long messageId, long userId);

    /**
 * Walk through the group door: one ticket, the human-checked subset of
 * the answer's cards, one core-side transaction .
 * The bridge forwards identity, ticket and ids only — anchors and
 * proposal payloads never leave core.
 */
    com.sixbean.testpike.admin.aichat.api.dto.DraftGroupApplyReceiptView applyGroupDrafts(
            long messageId, long userId, String confirmToken, java.util.List<Long> draftIds);

    /**
 * Scope-shaped twin of the group confirm door (AN batch piece D4-b;
 * core POST /internal/ai-chat/drafts/group/confirm-token). The wire names
 * either a dialog answer (message:{id}) or a system-origin group
 * (system:{projectId}:{objectType}) minted by the linkage bus; the
 * identity travels as the operator so core's per-row ownership guards
 * stay single-sourced.
 */
    DraftTicketView confirmGroupDraftsByScope(long userId, String groupScope);

    /** Scope-shaped twin of the group apply door (core POST .../group/apply). */
    com.sixbean.testpike.admin.aichat.api.dto.DraftGroupApplyReceiptView applyGroupDraftsByScope(
            long userId, String groupScope, String confirmToken, java.util.List<Long> draftIds);

    /**
     * Read side of the group door (core GET /internal/ai-chat/drafts/
     * group/pending?scope=...). Lists the group's pending cards for the
     * pinned panel BEFORE any ticket exists; empty group reads as empty list.
     */
    java.util.List<com.sixbean.testpike.admin.aichat.api.dto.PendingGroupCardView> groupPendingCards(
            long userId, String groupScope);

    /**
 * Freeze one owned session into a share snapshot :
 * core re-checks ownership against {@code userId} and clamps the ttl
 * into the 1h..7d window — the bridge forwards the request verbatim.
 */
    com.sixbean.testpike.admin.aichat.api.dto.ChatShareMirrorView createShare(
            long sessionId, long userId, int ttlHours);

    /** The owner's live share list for one session (revoked rows stay visible). */
    java.util.List<com.sixbean.testpike.admin.aichat.api.dto.ChatShareMirrorView> listShares(
            long sessionId, long userId);

    /** Owner-side kill switch for one share: idempotent state flip, core-side. */
    com.sixbean.testpike.admin.aichat.api.dto.ChatShareMirrorView revokeShare(
            long shareId, long userId);
}
