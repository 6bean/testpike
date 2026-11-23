package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.api.dto.ChatFrame;
import com.sixbean.testpike.core.aichat.api.dto.ChatMessageInfo;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.domain.component.ChatFrameType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSE chat frame protocol foundation for the online AI dialog .
 * <p>What the kernel must guarantee before any streaming endpoint is wired:
 * <ul>
 * <li>the adjudicated wire frame types exactly as pinned (state/reason/trace/
 * delta/draft/cite/heartbeat/done/error + user-inject) — the "never leave
 * the user waiting" contract;</li>
 * <li>one monotonic per-session frame sequence shared by every message, so the
 * client cursor (Last-Event-ID) is meaningful across the whole conversation;</li>
 * <li>no seq gaps even when two producers interleave under concurrency;</li>
 * <li>heartbeat frames are transport-only and must never consume a seq
 * (a heartbeat that burned seqs would silently shift every replay cursor);</li>
 * <li>replay from Last-Event-ID: still-streaming message → resend its whole
 * frame block; finished message → honest empty window (the client shows the
 * body instead, nothing is faked);</li>
 * <li>abort lands the partial message in status=aborted keeping everything
 * generated so far, closing its frame window, and is idempotent;</li>
 * <li>every read/write path is ownership-checked — a foreign user gets refused
 * with a named reason, never another user's session (horizontal access).</li>
 * </ul>
 * Real dev database, zero mocks (same posture as CommentServiceTest).
 */
@SpringBootTest(classes = CoreApplication.class)
class ChatFrameProtocolTest {

    @Autowired
    private ChatSessionService sessionService;

    @Autowired
    private ChatFrameService frameService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long newUserId(String tag) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, '$2a$10$x', ?, 1, 1) RETURNING id
                """, Long.class, tag, tag);
    }

    private long newProjectId(String tag) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, tag, tag);
    }

    private ChatSessionInfo freshSession() {
        String tag = "t426" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return sessionService.create(newProjectId(tag), newUserId(tag), "Frame protocol " + tag,
                1, "{\"route\":\"/cases\"}");
    }

    @Test
    void frameProtocolCarriesTheAdjudicatedFrameTypes() {
        // Mid-turn steering (AO frame-protocol adjudication, in-flight injection
        // clause) adds the wire type 'user-inject': a message the user sends
        // while a turn is running rides the protocol surface instead of being
        // silently dropped or faked as a normal submit. The multi-step plan card
        // (AO plan-card clause) adds 'plan': the step list proposed before a
        // long chain runs, confirmed or edited by the user before execution.
        Set<String> wireNames = List.of(ChatFrameType.values()).stream()
                .map(ChatFrameType::wireName)
                .collect(Collectors.toSet());
        // The clarification card adds 'clarify': the honest
        // question the AI leaves the turn on when a key slot is missing.
        assertEquals(Set.of("state", "reason", "trace", "delta", "draft",
                "cite", "heartbeat", "done", "error", "user-inject", "plan",
                "clarify"), wireNames);
    }

    @Test
    void appendFrameAllocatesMonotonicSeqSharedAcrossMessages() {
        ChatSessionInfo session = freshSession();
        long userMsg = frameService.openMessage(session.id(), "user", null);
        long assistantMsg = frameService.openMessage(session.id(), "assistant", null);

        long first = frameService.appendFrame(session.id(), assistantMsg,
                ChatFrameType.STATE, "已收到，正在路由模型", null);
        long second = frameService.appendFrame(session.id(), assistantMsg,
                ChatFrameType.DELTA, "正文增量一", null);
        assertTrue(second > first, "seq must grow inside one message");

        long third = frameService.appendFrame(session.id(), userMsg,
                ChatFrameType.TRACE, "tool_start 检索知识库", null);
        assertTrue(third > second, "seq must keep growing across messages in the session");

        long lastSeq = jdbcTemplate.queryForObject(
                "SELECT last_seq FROM tp_ai_chat_session WHERE id = ?", Long.class, session.id());
        assertEquals(third, lastSeq, "session cursor must equal the last allocated seq");
    }

    @Test
    void concurrentProducersNeverLeaveGapsInTheSequence() throws Exception {
        ChatSessionInfo session = freshSession();
        long message = frameService.openMessage(session.id(), "assistant", null);
        int producers = 4;
        int framesPerProducer = 5;

        ExecutorService pool = Executors.newFixedThreadPool(producers);
        try {
            List<Callable<List<Long>>> tasks = new java.util.ArrayList<>();
            for (int p = 0; p < producers; p++) {
                tasks.add(() -> {
                    List<Long> seqs = new java.util.ArrayList<>();
                    for (int i = 0; i < framesPerProducer; i++) {
                        seqs.add(frameService.appendFrame(session.id(), message,
                                ChatFrameType.DELTA, "p" + i, null));
                    }
                    return seqs;
                });
            }
            List<Future<List<Long>>> futures = pool.invokeAll(tasks);
            Set<Long> all = new java.util.TreeSet<>();
            for (Future<List<Long>> f : futures) {
                all.addAll(f.get());
            }
            assertEquals(producers * framesPerProducer, all.size(), "no duplicate seq");
            assertEquals(Long.valueOf(producers * framesPerProducer),
                    frameService.lastSeq(session.id()), "no gap: final cursor == frame count");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void heartbeatFrameIsNeverPersistedIntoTheLedger() {
        ChatSessionInfo session = freshSession();
        long message = frameService.openMessage(session.id(), "assistant", null);
        long before = frameService.lastSeq(session.id());

        assertThrows(ServiceException.class, () -> frameService.appendFrame(
                session.id(), message, ChatFrameType.HEARTBEAT, null, null));

        assertEquals(before, frameService.lastSeq(session.id()),
                "heartbeat must not consume a session seq");
    }

    @Test
    void replayResendsTheWholeBlockOfAStillStreamingMessage() {
        ChatSessionInfo session = freshSession();
        long message = frameService.openMessage(session.id(), "assistant", null);
        frameService.appendFrame(session.id(), message, ChatFrameType.STATE, "收单", null);
        frameService.appendFrame(session.id(), message, ChatFrameType.REASON, "分析中", null);
        frameService.appendFrame(session.id(), message, ChatFrameType.DELTA, "半截正文", null);

        // Cursor at the STATE frame → the client missed the two later frames only.
        long stateSeq = jdbcTemplate.queryForObject(
                "SELECT seq FROM tp_ai_chat_frame WHERE message_id = ? AND event_type = 'state'",
                Long.class, message);
        List<ChatFrame> replay = frameService.replayAfter(session.id(), stateSeq);
        assertEquals(2, replay.size(), "still-streaming block replays from its start, deduped by cursor");
        assertEquals(ChatFrameType.REASON, replay.get(0).eventType());
        assertEquals(ChatFrameType.DELTA, replay.get(1).eventType());
    }

    @Test
    void replayOfAFinishedMessageIsHonestAndEmpty() {
        ChatSessionInfo session = freshSession();
        long message = frameService.openMessage(session.id(), "assistant", null);
        frameService.appendFrame(session.id(), message, ChatFrameType.DELTA, "已完成正文", null);
        long doneSeq = frameService.appendFrame(session.id(), message,
                ChatFrameType.DONE, "done", "{\"total_tokens\":12}");
        frameService.closeMessage(message, "done");

        List<ChatFrame> replay = frameService.replayAfter(session.id(), doneSeq - 1);
        assertTrue(replay.isEmpty(),
                "finished block must not be re-faked as a live stream — the body is the answer");
    }

    @Test
    void abortKeepsGeneratedContentAndIsIdempotent() {
        ChatSessionInfo session = freshSession();
        long message = frameService.openMessage(session.id(), "assistant", null);
        long deltaSeq = frameService.appendFrame(session.id(), message,
                ChatFrameType.DELTA, "已生成的部分正文", null);

        ChatMessageInfo aborted = frameService.abort(message);
        assertEquals("aborted", aborted.status());
        assertEquals("已生成的部分正文", aborted.content(), "partial output is kept, never thrown away");

        Long seqTo = jdbcTemplate.queryForObject(
                "SELECT seq_to FROM tp_ai_chat_message WHERE id = ?", Long.class, message);
        assertEquals(Long.valueOf(deltaSeq), seqTo, "frame window closes at the last written frame");

        ChatMessageInfo again = frameService.abort(message);
        assertEquals("aborted", again.status());
        assertEquals(deltaSeq, again.seqTo().longValue(), "replayed abort changes nothing");
    }

    @Test
    void abortingACompletedMessageIsRefusedWithANamedReason() {
        ChatSessionInfo session = freshSession();
        long message = frameService.openMessage(session.id(), "assistant", null);
        frameService.appendFrame(session.id(), message, ChatFrameType.DONE, "done", null);
        frameService.closeMessage(message, "done");

        ServiceException ex = assertThrows(ServiceException.class, () -> frameService.abort(message));
        assertTrue(ex.getMessage().contains("已完成"), ex.getMessage());
    }

    @Test
    void messageBlockReplayResendsTheWholeRunEvenAfterTheWorkerSealedIt() {
        // The pump blind spot this pins: a fast worker (instant refusal, quick
        // answer) seals the block before the pump's first poll. The frames the
        // client never saw must still be delivered — dropping them would let the
        // pump fake a timeout the ledger disproves.
        ChatSessionInfo session = freshSession();
        long message = frameService.openMessage(session.id(), "assistant", null);
        frameService.appendFrame(session.id(), message, ChatFrameType.STATE, "收单", null);
        frameService.appendFrame(session.id(), message, ChatFrameType.DELTA, "正文", null);
        frameService.appendFrame(session.id(), message, ChatFrameType.DONE, "done", "{}");
        frameService.closeMessage(message, "done");

        List<ChatFrame> block = frameService.replayMessageBlock(session.userId(), message, 0L);

        assertEquals(3, block.size(),
                "the run this request opened replays in full even when sealed before the poll");
        assertEquals(ChatFrameType.DONE, block.get(2).eventType(),
                "the terminal frame must be present so the pump closes instead of idling");
    }

    @Test
    void messageBlockReplayRefusesAForeignUser() {
        ChatSessionInfo session = freshSession();
        long message = frameService.openMessage(session.id(), "assistant", null);
        frameService.appendFrame(session.id(), message, ChatFrameType.DELTA, "正文", null);
        long stranger = newUserId("t426b" + UUID.randomUUID().toString().substring(0, 8));

        assertThrows(ServiceException.class,
                () -> frameService.replayMessageBlock(stranger, message, 0L));
    }

    @Test
    void foreignUserIsRefusedAccessToAnotherUsersSession() {
        ChatSessionInfo session = freshSession();
        long stranger = newUserId("t426x" + UUID.randomUUID().toString().substring(0, 8));

        assertThrows(ServiceException.class,
                () -> sessionService.requireOwned(session.id(), stranger));
        assertThrows(ServiceException.class,
                () -> frameService.replayAfter(stranger, session.id(), 0L));
    }

    @Test
    void activeStreamingMessageDoorRevealsTheUnsealedTurnForReloadResume() {
        // Product defect (reload-during-stream): the sealed-history door leaves
        // the still-streaming turn out by design, so a reloaded page had NO way
        // to learn a turn is still running — the answer and the resume door
        // both vanished. This door is that missing signal: exactly one honest
        // read of "is there an unsealed assistant turn in this session".
        ChatSessionInfo session = freshSession();

        // Honest absence before anything is open: null, never a fake pointer.
        assertNull(frameService.activeStreamingMessage(session.userId(), session.id()));

        long userMsg = frameService.openMessage(session.id(), "user", null);
        frameService.closeMessage(userMsg, "done");
        long assistant = frameService.openMessage(session.id(), "assistant", null);
        frameService.appendFrame(session.id(), assistant, ChatFrameType.DELTA, "半截回答", null);

        ChatMessageInfo active = frameService.activeStreamingMessage(session.userId(), session.id());
        assertNotNull(active, "an unsealed streaming turn must be visible to its owner");
        assertEquals(assistant, active.id());
        assertEquals("assistant", active.role());
        assertEquals("streaming", active.status());

        // Sealed turns honestly disappear: the reload path folds them from the
        // ledger instead of offering a resume that would replay nothing.
        frameService.closeMessage(assistant, "done");
        assertNull(frameService.activeStreamingMessage(session.userId(), session.id()));

        // Horizontal posture (AF-g): a stranger is refused, never reads another
        // user's running turn.
        long stranger = newUserId("t426r" + UUID.randomUUID().toString().substring(0, 8));
        assertThrows(ServiceException.class,
                () -> frameService.activeStreamingMessage(stranger, session.id()));
    }

    @Test
    void contextWhitelistJsonMustBeAnObjectOrIsRejected() {
        // Two fresh projects: a repeated code would hit tp_project's UNIQUE first and
        // mask the whitelist refusal this test is actually about.
        String tag = "t426w" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        assertThrows(ServiceException.class, () -> sessionService.create(
                newProjectId(tag + "a"), newUserId(tag + "a"), "Bad context " + tag, 1, "[\"route\"]"));
        assertThrows(ServiceException.class, () -> sessionService.create(
                newProjectId(tag + "b"), newUserId(tag + "b"), "Bad context " + tag, 1, "not json"));
    }

    @Test
    void userInjectFrameIsLedgerPersistedWithItsOwnSeq() {
        // In-flight steering protocol surface: an injected user message consumes
        // a seq and lands a ledger row, so live stream, reconnect replay and
        // history reload all prove the injection was accepted — the "never
        // silently drop the user's mid-turn message" contract.
        ChatSessionInfo session = freshSession();
        long message = frameService.openMessage(session.id(), "assistant", null);
        long before = frameService.lastSeq(session.id());

        long seq = frameService.appendFrame(session.id(), message,
                ChatFrameType.USER_INJECT, "跳过第 3 步，先看失败批", null);

        assertTrue(seq > before, "user-inject must consume a session seq (ledger-persisted)");
        String stored = jdbcTemplate.queryForObject(
                "SELECT payload FROM tp_ai_chat_frame WHERE session_id = ? AND seq = ?",
                String.class, session.id(), seq);
        assertEquals("跳过第 3 步，先看失败批", stored, "injected text persists verbatim in the ledger");
    }

    @Test
    void ledgerCheckEnumeratesExactlyTheLedgerPersistedFrameTypes() {
        // Drift guard binding the wire enum to the DB CHECK: a new frame type
        // without its CHECK migration (or a CHECK widened without the enum) goes
        // red here instead of failing the first live write. Heartbeat stays out
        // of both lists on purpose (transport-only, never ledgered).
        String def = jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_ai_chat_frame_type'",
                String.class);
        Set<String> checkedList = new java.util.TreeSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("'([a-z-]+)'::character varying").matcher(def);
        while (m.find()) {
            checkedList.add(m.group(1));
        }
        Set<String> persisted = List.of(ChatFrameType.values()).stream()
                .filter(ChatFrameType::isLedgerPersisted)
                .map(ChatFrameType::wireName)
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        assertEquals(persisted, checkedList,
                "ledger CHECK and the enum's ledger-persisted set must enumerate the same frame types");
    }

    @Test
    void planFrameIsPartOfTheLedgerProtocol() {
        // Multi-step plan card: the step list the AI proposes before running a
        // long chain must survive a reload, so its frame is ledger-persisted
        // like the draft card frame, and the DB CHECK has to admit the literal
        // (a frame rejected by the constraint would kill the turn on ingest).
        ChatFrameType plan = ChatFrameType.fromWireName("plan");
        assertTrue(plan.isLedgerPersisted(),
                "the plan frame must be replayable from the ledger, not transport-only");
        String def = jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_ai_chat_frame_type'",
                String.class);
        assertTrue(def.contains("'plan'::character varying"),
                "the ledger CHECK must list the plan frame type: " + def);
    }

    @Test
    void clarifyFrameIsPartOfTheLedgerProtocol() {
        // Clarification card : the question the AI ends a turn
        // on must survive a reload exactly like the plan card, so its frame is
        // ledger-persisted and the DB CHECK has to admit the literal — a frame
        // rejected by the constraint would kill the turn on ingest.
        ChatFrameType clarify = ChatFrameType.fromWireName("clarify");
        assertTrue(clarify.isLedgerPersisted(),
                "the clarify frame must be replayable from the ledger, not transport-only");
        String def = jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_ai_chat_frame_type'",
                String.class);
        assertTrue(def.contains("'clarify'::character varying"),
                "the ledger CHECK must list the clarify frame type: " + def);
    }
}
