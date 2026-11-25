package com.sixbean.testpike.admin.aichat.api.controller;

import com.sixbean.testpike.admin.aichat.api.dto.ChatFeedbackRequest;
import com.sixbean.testpike.admin.aichat.api.dto.ChatInjectRequest;
import com.sixbean.testpike.admin.aichat.api.dto.ChatInjectView;
import com.sixbean.testpike.admin.aichat.api.dto.ChatMessageView;
import com.sixbean.testpike.admin.aichat.api.dto.ChatStreamRequest;
import com.sixbean.testpike.admin.aichat.app.component.SseFrameWriter;
import com.sixbean.testpike.admin.aichat.app.component.SseWindowSettings;
import com.sixbean.testpike.admin.aichat.app.service.ChatStreamBridge;
import com.sixbean.testpike.admin.aichat.infrastructure.remote.client.CoreAiChatClient;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Dialog stream door : the single POST-SSE
 * endpoint the chat dock consumes (fetch + ReadableStream — EventSource cannot
 * carry the JWT or the request body, adjudication §two), plus the interrupt
 * relay that seals a streaming turn honestly.
 * <p>Thin by rule: the pump lives in {@link ChatStreamBridge}; this class only
 * pins the login identity, opens the emitter, and closes the proxy-buffering
 * loophole ({@code X-Accel-Buffering: no}) so frames reach the browser live
 * instead of stalling in nginx. Session ownership is enforced by core (the
 * replay door is user-checked); admin does not keep a second copy.
 */
@RestController
@RequestMapping("/api/v1/ai/chat")
public class AiChatStreamController {

    private final ChatStreamBridge bridge;
    private final CoreAiChatClient coreClient;
    private final ProjectAccessGuard accessGuard;
    private final SseWindowSettings sseWindow;

    public AiChatStreamController(ChatStreamBridge bridge, CoreAiChatClient coreClient,
                                  ProjectAccessGuard accessGuard, SseWindowSettings sseWindow) {
        this.bridge = bridge;
        this.coreClient = coreClient;
        this.accessGuard = accessGuard;
        this.sseWindow = sseWindow;
    }

    /**
     * Ask a question or resume one in flight: fresh = body prompt, reconnect =
     * body resumeCursor (Last-Event-ID semantics, server trusts the body).
     */
    @PostMapping("/sessions/{sessionId}/stream")
    @RequirePermission("perm:aichat:use")
    public SseEmitter stream(@PathVariable long sessionId,
                             @RequestBody ChatStreamRequest request,
                             HttpServletResponse response) {
        long userId = UserContext.currentUserId();
        // Vertical verdict decided once here on the login session face
        // (SystemManagerRoles single source via the shared guard) — the run
        // message carries it as a DTO field, downstream services never
        // re-derive roles from a request context .
        boolean systemManager = accessGuard.isSystemManager(UserContext.current());
        boolean fresh = request.prompt() != null && !request.prompt().isBlank();
        if (!fresh && request.resumeCursor() == null) {
            throw new ServiceException("请填写问题内容，或携带续传游标重新连接本次回答。",
                    BizErrorCode.VALIDATION_ERROR);
        }
        // Proxy buffering would batch the frames and recreate the "staring at a
        // dead spinner" failure the protocol exists to prevent.
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        SseEmitter emitter = sseWindow.openEmitter();
        SseFrameWriter writer = new SseFrameWriter(emitter);
        // Virtual thread per stream: the pump blocks on ledger polls, which is
        // exactly what virtual threads are for; no bounded pool to starve.
        Thread.ofVirtual().name("ai-chat-stream-" + sessionId).start(() -> {
            try {
                bridge.stream(sessionId, userId, fresh ? request.prompt() : null,
                        fresh ? request.refObjects() : null,
                        fresh ? request.attachments() : null,
                        request.resumeCursor(), writer, systemManager,
                        fresh ? request.questionContext() : null);
            } catch (RuntimeException pumpDied) {
                writer.write("error", 0L,
                        "对话服务暂时不可用：" + pumpDied.getMessage(), null);
                writer.complete();
            }
        });
        return emitter;
    }

    /** Interrupt a streaming turn: partial output stays, the ledger seals as aborted. */
    @PostMapping("/messages/{messageId}/abort")
    @RequirePermission("perm:aichat:use")
    public Response<ChatMessageView> abort(@PathVariable long messageId) {
        // Identity is read for its 401 semantics; core re-checks ownership of the message.
        UserContext.currentUserId();
        return Response.builderSuccess(coreClient.abort(messageId));
    }

    /**
 * Steer the answer that is still streaming : the supplement lands on
 * the running turn as a journaled frame, so every viewer of this session
 * (reconnect, second tab, reload history) sees the user's words in the same
 * place. When no turn is running the text is submitted as a regular message
 * instead of being dropped — the response says which of the two happened.
 * Identity and the vertical verdict come from the login context only;
 * session ownership is re-checked core-side, the blank guard lives once there.
 */
    @PostMapping("/sessions/{sessionId}/injects")
    @RequirePermission("perm:aichat:use")
    public Response<ChatInjectView> inject(@PathVariable long sessionId,
                                           @RequestBody ChatInjectRequest request) {
        long userId = UserContext.currentUserId();
        boolean systemManager = accessGuard.isSystemManager(UserContext.current());
        return Response.builderSuccess(
                bridge.inject(sessionId, userId, request.content(), systemManager));
    }

    /**
     * Land the per-turn 👍/👎 signal (self-evolution loop observation intake):
     * the owner id comes from the login context only, core re-checks session
     * ownership and the value range — this door translates and forwards.
     */
    @PostMapping("/messages/{messageId}/feedback")
    @RequirePermission("perm:aichat:use")
    public Response<ChatMessageView> feedback(@PathVariable long messageId,
                                              @RequestBody ChatFeedbackRequest request) {
        long userId = UserContext.currentUserId();
        return Response.builderSuccess(
                coreClient.recordFeedback(messageId, userId, request.feedback(), request.reason()));
    }
}
