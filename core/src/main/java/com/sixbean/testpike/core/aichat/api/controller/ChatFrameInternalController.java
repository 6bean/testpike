package com.sixbean.testpike.core.aichat.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.api.dto.AppendFrameRequest;
import com.sixbean.testpike.core.aichat.api.dto.ChatFrame;
import com.sixbean.testpike.core.aichat.api.dto.ChatMessageInfo;
import com.sixbean.testpike.core.aichat.api.dto.CloseMessageRequest;
import com.sixbean.testpike.core.aichat.api.dto.InjectLanding;
import com.sixbean.testpike.core.aichat.api.dto.InjectUserRequest;
import com.sixbean.testpike.core.aichat.api.dto.OpenMessageRequest;
import com.sixbean.testpike.core.aichat.api.dto.RecordFeedbackRequest;
import com.sixbean.testpike.core.aichat.domain.component.ChatFrameType;
import com.sixbean.testpike.core.aichat.domain.service.ChatFrameService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * Internal frame-ledger door of the dialog kernel .
 * <p>Two consumers, one contract: the ai-service gateway writes the frame block
 * through {@code messages}/{@code frames} while the answer streams, and the
 * admin SSE orchestration reads it back through {@code replay}/{@code last-seq}
 * to serve reconnects (Last-Event-ID) and the abort command.
 * <p>Thin by rule: the seq allocation, replay window and abort semantics all
 * live in {@link ChatFrameService}; this class only translates the wire types.
 * The frame type crosses HTTP as its wire-name string — an unknown name is
 * refused here with a reason naming the legal values, never as a raw enum
 * exception. Replay is the ownership-checked overload (userId required), so a
 * foreign user cannot read another user's conversation through this door.
 */
@RestController
@RequestMapping("/internal/ai-chat")
public class ChatFrameInternalController {

    private final ChatFrameService frameService;

    public ChatFrameInternalController(ChatFrameService frameService) {
        this.frameService = frameService;
    }

    /** Open a turn (status=streaming); returns the message id. */
    @PostMapping("/sessions/{sessionId}/messages")
    public Response<Long> openMessage(@PathVariable long sessionId,
                                      @RequestBody OpenMessageRequest request) {
        return Response.builderSuccess(
                frameService.openMessage(sessionId, request.role(), request.content()));
    }

    /** Persist one ledger frame; returns its allocated session seq (the SSE {@code id:}). */
    @PostMapping("/sessions/{sessionId}/frames")
    public Response<Long> appendFrame(@PathVariable long sessionId,
                                      @RequestBody AppendFrameRequest request) {
        return Response.builderSuccess(frameService.appendFrame(sessionId, request.messageId(),
                parseFrameType(request.frameType()), request.payload(), request.metaJson()));
    }

    /** Current session cursor — the anchor a reconnecting client compares Last-Event-ID against. */
    @GetMapping("/sessions/{sessionId}/last-seq")
    public Response<Long> lastSeq(@PathVariable long sessionId) {
        return Response.builderSuccess(frameService.lastSeq(sessionId));
    }

    /** Reconnect replay after a cursor, ownership-checked by the calling user id. */
    @GetMapping("/sessions/{sessionId}/replay")
    public Response<List<ChatFrame>> replay(@PathVariable long sessionId,
                                             @RequestParam long cursor,
                                             @RequestParam long userId) {
        return Response.builderSuccess(frameService.replayAfter(userId, sessionId, cursor));
    }

    /**
     * Whole-block replay of one message: the pump's safety net for a run the
     * worker sealed before (or between) its polls — the ledger frames stay
     * deliverable instead of degrading into a fake timeout. Ownership-checked.
     */
    @GetMapping("/messages/{messageId}/replay")
    public Response<List<ChatFrame>> replayMessage(@PathVariable long messageId,
                                                    @RequestParam long cursor,
                                                    @RequestParam long userId) {
        return Response.builderSuccess(frameService.replayMessageBlock(userId, messageId, cursor));
    }

    /** Seal a turn with its terminal status (done/error). */
    @PostMapping("/messages/{messageId}/close")
    public Response<ChatMessageInfo> closeMessage(@PathVariable long messageId,
                                                  @RequestBody CloseMessageRequest request) {
        return Response.builderSuccess(frameService.closeMessage(messageId, request.status()));
    }

    /**
     * Conversation history of one session for a page reload: sealed turns, oldest
     * first, streamed answers rebuilt from the ledger. Ownership-checked by the
     * calling user id — a foreign user never reads another user's conversation
     * through this door (same AF-g posture as replay).
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public Response<List<ChatMessageInfo>> listMessages(@PathVariable long sessionId,
                                                        @RequestParam long userId) {
        return Response.builderSuccess(frameService.listSealedMessages(userId, sessionId));
    }

    /**
     * The unsealed streaming turn of one session (reload-resume signal): the
     * owner gets the streaming message view or an honest null; a foreign user
     * is refused by the ownership check inside the service.
     */
    @GetMapping("/sessions/{sessionId}/active-message")
    public Response<ChatMessageInfo> activeMessage(@PathVariable long sessionId,
                                                   @RequestParam long userId) {
        return Response.builderSuccess(frameService.activeStreamingMessage(userId, sessionId));
    }

    /** Interrupt a streaming turn honestly: partial output kept, window sealed, idempotent. */
    @PostMapping("/messages/{messageId}/abort")
    public Response<ChatMessageInfo> abort(@PathVariable long messageId) {
        return Response.builderSuccess(frameService.abort(messageId));
    }

    /** Single-column status read behind the run-side abort checkpoints . */
    @GetMapping("/messages/{messageId}/status")
    public Response<String> messageStatus(@PathVariable long messageId) {
        return Response.builderSuccess(frameService.messageStatus(messageId));
    }

    /**
 * Mid-turn steering door : land the user's supplement on the turn
 * that is currently streaming. A null payload is the honest degrade
 * signal - no turn is running, and the caller re-sends the text through
 * the regular message channel instead of losing it.
 */
    @PostMapping("/sessions/{sessionId}/injects")
    public Response<InjectLanding> injectUser(@PathVariable long sessionId,
                                              @RequestBody InjectUserRequest request) {
        return Response.builderSuccess(
                frameService.injectUserMessage(sessionId, request.userId(), request.content()));
    }

    /** The running worker's read door: unconsumed injections above its cursor. */
    @GetMapping("/sessions/{sessionId}/pending-injects")
    public Response<List<ChatFrame>> pendingInjects(@PathVariable long sessionId,
                                                    @RequestParam long afterSeq) {
        return Response.builderSuccess(frameService.findInjectsAfter(sessionId, afterSeq));
    }

    /**
 * Cross-message image replay read door : the raw
 * meta_json strings of the newest {@code chatImages} bookings of this
 * session, newest first. Ownership is re-checked inside the service —
 * this door translates only, and the ai-service scanner is the single
 * parser of the structured value.
 */
    @GetMapping("/sessions/{sessionId}/chat-images")
    public Response<List<String>> chatImageMetas(@PathVariable long sessionId,
                                                 @RequestParam long userId,
                                                 @RequestParam(defaultValue = "3") int limit) {
        return Response.builderSuccess(frameService.findChatImageMetas(userId, sessionId, limit));
    }

    /**
     * Land the per-turn 👍/👎 observation signal (self-evolution loop intake).
     * The owner id rides the body from the admin login context — the service
     * re-checks session ownership against it (AF-g), this door translates only.
     */
    @PostMapping("/messages/{messageId}/feedback")
    public Response<ChatMessageInfo> recordFeedback(@PathVariable long messageId,
                                                    @RequestBody RecordFeedbackRequest request) {
        return Response.builderSuccess(frameService.recordFeedback(messageId,
                request.userId(), request.feedback(), request.reason()));
    }

    /** Wire name → ledger type, with a human-readable refusal that names the legal values. */
    private ChatFrameType parseFrameType(String wireName) {
        try {
            return ChatFrameType.fromWireName(wireName);
        } catch (IllegalArgumentException ex) {
            throw new ServiceException("未知的帧类型：" + wireName + "，合法值="
                    + Arrays.toString(Arrays.stream(ChatFrameType.values())
                            .map(ChatFrameType::wireName).toArray()),
                    BizErrorCode.VALIDATION_ERROR);
        }
    }
}
