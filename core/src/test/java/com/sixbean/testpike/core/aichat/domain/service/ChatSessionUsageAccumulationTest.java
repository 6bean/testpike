package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.domain.component.ChatFrameType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Session token ledger written at the done-frame point (E9 session attribution,
 * AO batch phase 1): the done frame is the one place a turn's usage
 * numbers land in the ledger, so {@code tp_ai_chat_session.total_tokens}
 * accumulates there with an atomic increment — a read-modify-write would lose
 * a concurrent turn's tokens. A done payload without parseable usage JSON (a
 * legacy shape, or the round-limit error posture) never touches the ledger:
 * honest zeros beat a fabricated number.
 * <p>Real dev database, zero mocks (same posture as {@link
 * ChatFrameProtocolTest}).
 */
@SpringBootTest(classes = CoreApplication.class)
class ChatSessionUsageAccumulationTest {

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
        String tag = "t435" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return sessionService.create(newProjectId(tag), newUserId(tag), "Usage ledger " + tag,
                1, "{}");
    }

    private long totalTokensOf(long sessionId) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT total_tokens FROM tp_ai_chat_session WHERE id = ?", Long.class, sessionId);
        return value == null ? -1L : value;
    }

    private void appendDone(long sessionId, long messageId, String payload) {
        frameService.appendFrame(sessionId, messageId, ChatFrameType.DONE, payload, null);
    }

    @Test
    void doneFrameAccumulatesTheSessionTokenLedger() {
        ChatSessionInfo session = freshSession();
        long first = frameService.openMessage(session.id(), "assistant", null);
        appendDone(session.id(), first,
                "{\"messageId\":" + first + ",\"promptTokens\":120,\"completionTokens\":34}");
        assertEquals(154L, totalTokensOf(session.id()),
                "the done frame's usage must land on the session total");

        long second = frameService.openMessage(session.id(), "assistant", null);
        appendDone(session.id(), second,
                "{\"messageId\":" + second + ",\"promptTokens\":10,\"completionTokens\":5}");
        assertEquals(169L, totalTokensOf(session.id()),
                "a later turn adds to the ledger instead of replacing it");

        // The view the doors serve carries the same number (single source).
        assertEquals(169L, sessionService.findById(session.id()).totalTokens());
    }

    @Test
    void usagelessFramesLeaveTheLedgerUntouched() {
        ChatSessionInfo session = freshSession();
        long message = frameService.openMessage(session.id(), "assistant", null);
        // A delta and a done with a non-JSON payload (legacy/error posture):
        // neither may move the ledger, and neither may break the frame write.
        frameService.appendFrame(session.id(), message, ChatFrameType.DELTA, "正文增量", null);
        appendDone(session.id(), message, "回答生成失败：provider unreachable");
        assertEquals(0L, totalTokensOf(session.id()),
                "a done frame without usage JSON keeps the honest zero");

        long second = frameService.openMessage(session.id(), "assistant", null);
        appendDone(session.id(), second, "{\"promptTokens\":0,\"completionTokens\":0}");
        assertEquals(0L, totalTokensOf(session.id()),
                "explicit zero usage must not fabricate a ledger movement");
    }

    @Test
    void doneFrameRecordsUsageOnTheMessageRow() {
        // Per-message attribution (E9 cost view reconciles session totals
        // against message rows): the same usage payload that feeds the
        // session total also lands on the assistant message it belongs to,
        // so a billing read never depends on two write paths agreeing.
        ChatSessionInfo session = freshSession();
        long message = frameService.openMessage(session.id(), "assistant", null);
        appendDone(session.id(), message, "{\"promptTokens\":210,\"completionTokens\":40}");
        assertEquals(List.of(210L, 40L), messageUsageOf(message),
                "the done frame's usage must land on its own message row");

        // An unparseable usage posture (error frame body as done payload)
        // leaves the message on its honest zeros instead of fabricating.
        long second = frameService.openMessage(session.id(), "assistant", null);
        appendDone(session.id(), second, "回答生成失败：provider unreachable");
        assertEquals(List.of(0L, 0L), messageUsageOf(second),
                "no usage JSON means no message-level numbers");
    }

    private List<Long> messageUsageOf(long messageId) {
        return jdbcTemplate.query(
                "SELECT prompt_tokens, completion_tokens FROM tp_ai_chat_message WHERE id = ?",
                (rs, rowNum) -> List.of(rs.getLong(1), rs.getLong(2)), messageId).get(0);
    }

    @Test
    void concurrentTurnsNeverLoseAccumulatedTokens() throws Exception {
        ChatSessionInfo session = freshSession();
        int producers = 4;
        int framesPerProducer = 3;
        List<Long> messages = new java.util.ArrayList<>();
        for (int i = 0; i < producers * framesPerProducer; i++) {
            messages.add(frameService.openMessage(session.id(), "assistant", null));
        }
        ExecutorService pool = Executors.newFixedThreadPool(producers);
        try {
            List<Callable<Void>> tasks = new java.util.ArrayList<>();
            for (int p = 0; p < producers; p++) {
                final int base = p * framesPerProducer;
                tasks.add(() -> {
                    for (int i = 0; i < framesPerProducer; i++) {
                        long message = messages.get(base + i);
                        appendDone(session.id(), message,
                                "{\"promptTokens\":2,\"completionTokens\":1}");
                    }
                    return null;
                });
            }
            for (Future<Void> future : pool.invokeAll(tasks)) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(3L * producers * framesPerProducer, totalTokensOf(session.id()),
                "atomic increment: every concurrent done frame counts exactly once");
    }
}
