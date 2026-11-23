package com.sixbean.testpike.admin.aichat.api.controller;

import com.sixbean.testpike.admin.aichat.api.dto.ChatExportMirrorView;
import com.sixbean.testpike.admin.aichat.api.dto.ChatMessageView;
import com.sixbean.testpike.admin.aichat.api.dto.ChatSedimentMirrorView;
import com.sixbean.testpike.admin.aichat.api.dto.ChatSessionPageMirrorView;
import com.sixbean.testpike.admin.aichat.api.dto.ChatSessionView;
import com.sixbean.testpike.admin.aichat.api.dto.ChatShareMirrorView;
import com.sixbean.testpike.admin.aichat.api.dto.SessionCreateRequest;
import com.sixbean.testpike.admin.aichat.api.dto.SessionRenameRequest;
import com.sixbean.testpike.admin.aichat.app.component.ChatSessionGroupDays;
import com.sixbean.testpike.admin.aichat.infrastructure.remote.client.CoreAiChatClient;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.core.infrastructure.remote.client.CoreUserProjectClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Dialog-session door for the chat dock : opens a
 * conversation before the browser attaches to the SSE stream.
 * <p>Thin by rule — the operator id comes from the login context (a body field
 * can never speak for another user), the H1 project guard runs before anything
 * reaches core, and every semantic validation (title bounds, source range,
 * context whitelist) stays single-sourced in core: no second copy here.
 * The default entry surface is the floating dock, per the entry-shape ruling.
 */
@RestController
@RequestMapping("/api/v1/ai/chat")
public class AiChatSessionController {

    /** Entry surface 1 = the global dock (the only caller of this lane today). */
    private static final int SOURCE_DOCK = 1;

    private final CoreAiChatClient coreClient;
    private final ProjectAccessGuard accessGuard;
    private final CoreUserProjectClient userProjectClient;

    public AiChatSessionController(CoreAiChatClient coreClient, ProjectAccessGuard accessGuard,
                                   CoreUserProjectClient userProjectClient) {
        this.coreClient = coreClient;
        this.accessGuard = accessGuard;
        this.userProjectClient = userProjectClient;
    }

    /** Open a conversation inside the caller's project segment (H1-guarded). */
    @PostMapping("/sessions")
    @RequirePermission("perm:aichat:use")
    public Response<ChatSessionView> create(@RequestBody SessionCreateRequest request) {
        CurrentUser user = UserContext.current();
        accessGuard.assertCanView(user, request.projectId());
        int source = request.source() == null ? SOURCE_DOCK : request.source();
        return Response.builderSuccess(coreClient.createSession(request.projectId(), user.userId(),
                request.title(), source, request.contextJson()));
    }

    /**
     * Conversation history of one session (page reload): sealed turns oldest
     * first, streamed answers already rebuilt core-side. Ownership is enforced
     * by the core door (same AF-g posture as replay) — admin keeps no second
     * copy, only the login identity that anchors the check.
     */
    @GetMapping("/sessions/{sessionId}/messages")
    @RequirePermission("perm:aichat:use")
    public Response<List<ChatMessageView>> history(@PathVariable long sessionId) {
        long userId = UserContext.currentUserId();
        return Response.builderSuccess(coreClient.listHistory(sessionId, userId));
    }

    /**
     * Reload-resume signal: the still-unsealed streaming turn of one owned
     * session (or null). The reloaded page reads this right after the sealed
     * history to decide whether to restore the resume entry — the streaming
     * turn is never doubled here, the SSE replay door owns its content.
     */
    @GetMapping("/sessions/{sessionId}/active-message")
    @RequirePermission("perm:aichat:use")
    public Response<ChatMessageView> activeMessage(@PathVariable long sessionId) {
        long userId = UserContext.currentUserId();
        return Response.builderSuccess(coreClient.activeMessage(sessionId, userId));
    }

    /**
 * Dialog history list for the logged-in user — the door the
 * session panel pages, searches and groups by date. Permission scope
 * = user_id (bound structurally core-side) ∧ the requested project segment
 * pruned to the caller's visible domain: a project id the caller cannot
 * see is dropped from the filter instead of leaking sessions through a
 * typo'd or forged segment (the visible-domain list is the SAME source the
 * header project picker feeds, no second membership copy here).
 */
    @GetMapping("/sessions")
    @RequirePermission("perm:aichat:use")
    public Response<ChatSessionPageMirrorView> list(
            @RequestParam(required = false) List<Long> projectIds,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer source,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        CurrentUser user = UserContext.current();
        if (projectIds != null && !projectIds.isEmpty()) {
            // A named segment is intersected with the caller's visible domain. An
            // intersection that comes back EMPTY answers an empty page right
            // here — forwarding the empty list would read as "no segment filter"
            // core-side and quietly return every project (wrong semantics,
            // though never another user's rows).
            List<Long> kept = intersectVisibleDomain(user, projectIds);
            if (kept.isEmpty()) {
                return Response.builderSuccess(new ChatSessionPageMirrorView(List.of(), 0, page, pageSize));
            }
            return Response.builderSuccess(coreClient.listSessions(user.userId(), kept, status,
                    source, keyword, page, pageSize));
        }
        return Response.builderSuccess(coreClient.listSessions(user.userId(), List.of(), status,
                source, keyword, page, pageSize));
    }

    /** Rename one owned session ; the owner id comes from the login context only. */
    @PatchMapping("/sessions/{sessionId}")
    @RequirePermission("perm:aichat:use")
    public Response<ChatSessionView> rename(@PathVariable long sessionId,
                                            @RequestBody SessionRenameRequest request) {
        long userId = UserContext.currentUserId();
        return Response.builderSuccess(coreClient.renameSession(sessionId, userId, request.title()));
    }

    /** Archive one owned session : history stays, status parks; idempotent. */
    @PostMapping("/sessions/{sessionId}/archive")
    @RequirePermission("perm:aichat:use")
    public Response<ChatSessionView> archive(@PathVariable long sessionId) {
        long userId = UserContext.currentUserId();
        return Response.builderSuccess(coreClient.archiveSession(sessionId, userId));
    }

    /**
 * Markdown export of one owned session (AO batch phase 2, E5
 * sedimentation): the reader gets their own conversation as a document —
 * masked core-side, so credentials and PII never ride out. The owner id
 * comes from the login context only (a body field cannot speak for another
 * user), and the ownership refusal itself stays single-sourced in core.
 * Export writes nothing: landing content into the knowledge base is the
 * separate confirmed path, never a side effect of downloading.
 */
    @GetMapping("/sessions/{sessionId}/export")
    @RequirePermission("perm:aichat:use")
    public Response<ChatExportMirrorView> exportSession(@PathVariable long sessionId) {
        long userId = UserContext.currentUserId();
        return Response.builderSuccess(coreClient.exportSession(sessionId, userId));
    }

    /**
 * Context waterline of one owned session : the waterline
 * bar numbers for the dialog panel, read server-side — the browser never
 * recomputes the budget or the fold. The owner id comes from the login
 * context only; the refusal of a foreign session stays single-sourced in
 * core.
 */
    @GetMapping("/sessions/{sessionId}/context-waterline")
    @RequirePermission("perm:aichat:use")
    public Response<com.sixbean.testpike.admin.aichat.api.dto.ChatContextWaterlineMirrorView> contextWaterline(
            @PathVariable long sessionId) {
        long userId = UserContext.currentUserId();
        return Response.builderSuccess(coreClient.contextWaterline(sessionId, userId));
    }

    /**
 * Save one owned session's dialog into the knowledge base (AO batch
 * phase 2b, E5 sedimentation): the conversation becomes a real document
 * the user can find in the AI document management page. Like the export,
 * the owner id
 * comes from the login context only and every refusal (foreign session,
 * dialog without Q&A content yet, same-minute re-save) is answered by core
 * — this door stays a pure translation point with no second judgment copy.
 */
    @PostMapping("/sessions/{sessionId}/sediment")
    @RequirePermission("perm:aichat:use")
    public Response<ChatSedimentMirrorView> sedimentSession(@PathVariable long sessionId) {
        long userId = UserContext.currentUserId();
        return Response.builderSuccess(coreClient.sedimentSession(sessionId, userId));
    }

    /**
 * One-key undo of the session's last accepted case write (AM batch
 * : the operator id comes from the login context only (a body
 * field can never speak for another user), ownership and every undo
 * refusal are answered core-side — this lane is a pure translation point.
 * The answer is the id of a NEW pending rollback card: nothing has rolled
 * back yet, the human confirmation door remains the only writer (red line
 * of the AM batch: the write-confirmation gate is never bypassed).
 */
    @PostMapping("/sessions/{sessionId}/undo")
    @RequirePermission("perm:aichat:use")
    public Response<Long> undo(@PathVariable long sessionId) {
        long userId = UserContext.currentUserId();
        return Response.builderSuccess(coreClient.undoSession(sessionId, userId));
    }

    // ---- dialog snapshot shares ----

    /**
 * Freeze one owned session into a read-only share snapshot :
 * the copy is rendered and masked core-side at this moment, so the link
 * handed out later never follows the live session. The owner id comes
 * from the login context only; the ttl request (hours) is forwarded
 * verbatim — the 1h..7d clamp is single-sourced in core.
 */
    @PostMapping("/sessions/{sessionId}/share")
    @RequirePermission("perm:aichat:use")
    public Response<ChatShareMirrorView> createShare(@PathVariable long sessionId,
                                                     @RequestParam int ttlHours) {
        long userId = UserContext.currentUserId();
        return Response.builderSuccess(coreClient.createShare(sessionId, userId, ttlHours));
    }

    /** Live shares of one owned session, newest first (revoked rows visible). */
    @GetMapping("/sessions/{sessionId}/shares")
    @RequirePermission("perm:aichat:use")
    public Response<List<ChatShareMirrorView>> listShares(@PathVariable long sessionId) {
        long userId = UserContext.currentUserId();
        return Response.builderSuccess(coreClient.listShares(sessionId, userId));
    }

    /** Kill one share link (owner-side, idempotent; the audit row stays). */
    @DeleteMapping("/shares/{shareId}")
    @RequirePermission("perm:aichat:use")
    public Response<ChatShareMirrorView> revokeShare(@PathVariable long shareId) {
        long userId = UserContext.currentUserId();
        return Response.builderSuccess(coreClient.revokeShare(shareId, userId));
    }

    /**
 * Date-group bucket boundaries for the session list : one K8
 * hot key read through the core config door, parsed single-sourced in
 * ChatSessionGroupDays. A malformed or unreadable value answers the
 * documented default — the panel never sees a broken grouping, and the
 * default exists in exactly one place (the seed row shares it).
 */
    @GetMapping("/sessions/group-days")
    @RequirePermission("perm:aichat:use")
    public Response<Map<String, List<Integer>>> groupDays() {
        String raw = coreClient.readConfigString(ChatSessionGroupDays.CONFIG_KEY,
                ChatSessionGroupDays.DEFAULT_SPEC);
        return Response.builderSuccess(Map.of("buckets", ChatSessionGroupDays.parseOrFallback(raw)));
    }

    /**
 * Intersect the asked segment with the caller's visible domain — the SAME
 * judgment source as the header project picker (core user-project query,
 * ADMIN/TEST_LEAD see all active projects): no second membership copy.
 * An id outside the visible set is dropped (sessions are owner-scoped
 * anyway; this only narrows which of MY threads a filter may name).
 */
    private List<Long> intersectVisibleDomain(CurrentUser user, List<Long> projectIds) {
        boolean systemManager = accessGuard.isSystemManager(user);
        List<Long> visible = userProjectClient.listUserProjects(user.userId(), systemManager)
                .stream().map(p -> p.projectId()).toList();
        return projectIds.stream().filter(visible::contains).toList();
    }
}
