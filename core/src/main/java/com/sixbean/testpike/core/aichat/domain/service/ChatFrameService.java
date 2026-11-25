package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.core.aichat.api.dto.ChatFrame;
import com.sixbean.testpike.core.aichat.api.dto.ChatMessageInfo;
import com.sixbean.testpike.core.aichat.api.dto.InjectLanding;
import com.sixbean.testpike.core.aichat.domain.component.ChatFrameType;

import java.util.List;

/**
 * Frame ledger service : allocates the monotonic per-session
 * sequence, persists the replayable frame block, replays the still-streaming
 * tail after a reconnect, and lands abort as an honest partial state.
 * <p>Sequence discipline: one counter per session shared by all messages
 * (session row lock, {@code SELECT ... FOR UPDATE} then increment) — no gaps,
 * no reuse, so a client cursor is always meaningful. Heartbeats never allocate.
 */
public interface ChatFrameService {

    /** Open a turn (status streaming); {@code role} ∈ user/assistant/system. */
    long openMessage(long sessionId, String role, String content);

    /**
 * Open a turn journalling the per-question whitelist page anchor on the
 * row — the audit answer to "which page was this
 * question asked from". The payload is already validated at the run door
 * (the ONE whitelist point); a null context keeps the bare old shape.
 */
    default long openMessage(long sessionId, String role, String content, String questionContextJson) {
        return openMessage(sessionId, role, content);
    }

    /**
     * Land the per-turn 👍/👎 observation signal on one message (self-evolution
     * loop intake): {@code feedback} 1 helpful / -1 not helpful / 0 cleared.
     * The one-sentence {@code reason} is only kept with a -1 rating — switching
     * to 👍 or toggling off clears any stale complaint so the ledger never
     * carries a mixed signal. Ownership is re-checked against {@code userId}
     * (horizontal red line AF-g), unknown message ids and out-of-range values
     * are refused with a named reason.
     */
    ChatMessageInfo recordFeedback(long messageId, long userId, int feedback, String reason);

    /**
     * Persist one frame and return its allocated session seq (the SSE {@code id:}).
     * Refuses non-ledger types (heartbeat) with a named reason.
     * Skip sentinel: when the message row is already gone (a concurrent purge
     * retired the conversation mid-turn) no frame lands and {@code 0} is
     * returned — callers treat 0 as "this turn's ledger no longer exists, end
     * the stream quietly" instead of catching a data-integrity error per frame.
     */
    long appendFrame(long sessionId, long messageId, ChatFrameType type, String payload, String metaJson);

    /** Current session cursor. */
    long lastSeq(long sessionId);

    /**
     * Reconnect replay for a cursor (Last-Event-ID): the frames of the currently
     * streaming message that the client has not acknowledged yet. When every
     * block is finished the result is empty — a completed answer is served as the
     * message body, never re-faked as a live stream.
     */
    List<ChatFrame> replayAfter(long sessionId, long cursor);

    /**
     * Whole-block replay of one message after a cursor, ownership-checked by the
     * requesting user. Unlike {@link #replayAfter(long, long)} this ignores the
     * streaming status: a run the worker sealed in milliseconds (instant refusal,
     * fast answer) still carries the frames the pump never polled — dropping them
     * would let the SSE pump serve a timeout the ledger disproves.
     */
    List<ChatFrame> replayMessageBlock(long userId, long messageId, long cursor);

    /** Ownership-checked replay for the admin-facing path (AF-g). */
    List<ChatFrame> replayAfter(long userId, long sessionId, long cursor);

    /** Close a turn with its terminal status (done/error) and seal the frame window. */
    ChatMessageInfo closeMessage(long messageId, String status);

    /**
     * Conversation history for a reload: every sealed turn of the session, oldest
     * first, ownership-checked. An assistant turn whose body was streamed as
     * delta frames (done/error sealed — only aborted writes the partial back)
     * gets its answer rebuilt from the ledger, so the page a user comes back to
     * shows exactly what they watched live. The still-streaming turn is left out
     * — the live SSE pump owns it, replaying it here would double-render.
     */
    List<ChatMessageInfo> listSealedMessages(long userId, long sessionId);

    /**
     * The one honest answer to "is a turn still running in this session": the
     * single unsealed streaming message (assistant turn in status=streaming)
     * for the session owner, or null when every turn is sealed. A reloaded
     * page cannot learn this from {@link #listSealedMessages} — the streaming
     * turn is deliberately left out there to avoid double rendering — so this
     * door is the missing signal that lets the client restore its resume entry
     * and replay the running answer through the existing SSE path. Ownership
     * is re-checked against {@code userId} (AF-g), a stranger is refused.
     */
    ChatMessageInfo activeStreamingMessage(long userId, long sessionId);

    /**
     * Abort a still-streaming turn: keeps every delta/reason generated so far as
     * the message body, seals the window, status=aborted, idempotent. Billing
     * stops upstream — nothing is pretended complete and nothing is discarded.
     */
    ChatMessageInfo abort(long messageId);

    /**
 * Current status string of one message row (streaming/done/error/aborted).
 * The cheap read behind the dialog run loop's abort checkpoints :
 * the abort command's seal IS the cancel record, the running worker just
 * observes it at its existing round/batch boundaries.
 */
    String messageStatus(long messageId);

    /**
 * Land a mid-turn steering injection on the turn that is currently
 * streaming: the text becomes a durable user-inject frame on the ledger -
 * the acceptance proof the worker will pick up at its next turn boundary.
 * Null return = no turn is streaming, and the honest contract is that
 * nothing is journalled (the caller degrades the text to a regular
 * message instead of dropping it). Blank text and foreign sessions are
 * refused fail-closed.
 */
    InjectLanding injectUserMessage(long sessionId, long userId, String content);

    /**
     * The worker's read door: user-inject frames above its cursor, oldest
     * first. Other frame types never appear here - the answer stream stays on
     * the replay door (single source per consumer).
     */
    List<ChatFrame> findInjectsAfter(long sessionId, long afterSeq);

    /**
 * The cross-message image replay read door : the
 * raw meta_json strings of the newest state frames that booked a
 * {@code chatImages} array, newest seq first, at most {@code limit}
 * rows, ownership re-checked against {@code userId} (AF-g). The scanner
 * on the ai-service side parses the structured value — this service only
 * answers "which ledger rows carry the booking", never re-formats it.
 * The default empty list keeps every existing fake/test wiring honest:
 * no booked pictures means nothing to replay.
 */
    default List<String> findChatImageMetas(long userId, long sessionId, int limit) {
        return List.of();
    }
}
