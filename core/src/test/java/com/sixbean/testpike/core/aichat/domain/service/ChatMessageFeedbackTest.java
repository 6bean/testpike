package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.api.dto.ChatMessageInfo;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Per-turn thumbs feedback intake (self-evolution loop, observation signal
 * face): the 👍/👎 a user leaves on one answer lands on the message ledger row
 * and reads back through the same door that serves history.
 * <p>What this pins on the real dev database with zero mocks (same posture as
 * {@code ChatSessionManagementTest}):
 * <ul>
 *   <li>👍 lands 1 with no reason line; 👎 lands -1 and keeps the optional
 *       one-sentence reason (the "answer inaccurate" signal);</li>
 *   <li>switching 👎→👍 clears the stale complaint, and toggling off (0)
 *       clears both — one honest row state, never a mixed signal;</li>
 *   <li>values outside -1/0/1 and oversized reasons are refused fail-closed;</li>
 *   <li>the horizontal red line: a stranger rating someone else's turn gets a
 *       named ownership refusal and the row stays untouched;</li>
 *   <li>a message id that never existed is refused with a named reason.</li>
 * </ul>
 * Fixtures create random-tagged users/projects so demo projects stay untouched.
 */
@SpringBootTest(classes = CoreApplication.class)
class ChatMessageFeedbackTest {

    @Autowired
    private ChatFrameService frameService;

    @Autowired
    private ChatSessionService sessionService;

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

    private String tag(String stem) {
        return stem + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /** One owned session carrying a sealed assistant answer to rate. */
    private long[] openRatedTurn() {
        String user = tag("fbu");
        long userId = newUserId(user);
        long projectId = newProjectId(tag("fbp"));
        ChatSessionInfo session = sessionService.create(projectId, userId, "反馈采集", 1, "{}");
        long messageId = frameService.openMessage(session.id(), "assistant", "答案正文");
        frameService.closeMessage(messageId, "done");
        return new long[]{userId, messageId};
    }

    @Test
    void thumbsUpLandsWithoutAReasonLine() {
        long[] turn = openRatedTurn();
        ChatMessageInfo after = frameService.recordFeedback(turn[1], turn[0], 1, null);
        assertEquals(Integer.valueOf(1), after.feedback());
        assertNull(after.feedbackReason());
        Integer stored = jdbcTemplate.queryForObject(
                "SELECT feedback FROM tp_ai_chat_message WHERE id = ?", Integer.class, turn[1]);
        assertEquals(Integer.valueOf(1), stored, "the ledger row itself must carry the signal");
    }

    @Test
    void thumbsDownKeepsTheOptionalOneSentenceReason() {
        long[] turn = openRatedTurn();
        ChatMessageInfo after = frameService.recordFeedback(turn[1], turn[0], -1, "  引用的版本已经过期  ");
        assertEquals(Integer.valueOf(-1), after.feedback());
        assertEquals("引用的版本已经过期", after.feedbackReason(),
                "the complaint sentence is the observation payload - stripped, never dropped");
    }

    @Test
    void switchingToThumbsUpClearsTheStaleComplaint() {
        long[] turn = openRatedTurn();
        frameService.recordFeedback(turn[1], turn[0], -1, "答非所问");
        ChatMessageInfo after = frameService.recordFeedback(turn[1], turn[0], 1, null);
        assertEquals(Integer.valueOf(1), after.feedback());
        assertNull(after.feedbackReason(),
                "a positive signal must never ride on the previous complaint text");
    }

    @Test
    void togglingOffClearsBothSignalColumns() {
        long[] turn = openRatedTurn();
        frameService.recordFeedback(turn[1], turn[0], -1, "不准确");
        ChatMessageInfo after = frameService.recordFeedback(turn[1], turn[0], 0, null);
        assertEquals(Integer.valueOf(0), after.feedback());
        assertNull(after.feedbackReason());
    }

    @Test
    void valuesOutsideTheThreeStatesAreRefused() {
        long[] turn = openRatedTurn();
        assertThrows(ServiceException.class,
                () -> frameService.recordFeedback(turn[1], turn[0], 2, null));
        Integer untouched = jdbcTemplate.queryForObject(
                "SELECT feedback FROM tp_ai_chat_message WHERE id = ?", Integer.class, turn[1]);
        assertNull(untouched, "a refused write must land nothing on the ledger");
    }

    @Test
    void oversizedReasonIsRefusedFailClosed() {
        long[] turn = openRatedTurn();
        assertThrows(ServiceException.class, () -> frameService.recordFeedback(
                turn[1], turn[0], -1, "超".repeat(600)));
    }

    @Test
    void aStrangerCannotRateAnotherUsersTurn() {
        long[] turn = openRatedTurn();
        long strangerId = newUserId(tag("fbs"));
        ServiceException refusal = assertThrows(ServiceException.class,
                () -> frameService.recordFeedback(turn[1], strangerId, 1, null));
        assertEquals(Integer.valueOf(40301), refusal.getCode(),
                "horizontal access on the conversation face uses the named ownership refusal");
        Integer untouched = jdbcTemplate.queryForObject(
                "SELECT feedback FROM tp_ai_chat_message WHERE id = ?", Integer.class, turn[1]);
        assertNull(untouched);
    }

    @Test
    void anUnknownMessageIsRefusedWithANamedReason() {
        long[] turn = openRatedTurn();
        assertThrows(ServiceException.class,
                () -> frameService.recordFeedback(turn[1] + 999_999L, turn[0], 1, null));
    }
}
