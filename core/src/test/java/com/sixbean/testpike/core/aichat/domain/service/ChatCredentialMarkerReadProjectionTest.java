package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.api.dto.ChatMessageInfo;
import com.sixbean.testpike.core.aichat.domain.component.CredentialScreenshotGovernance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Credential-screenshot marker read projection (BD batch piece E,
 * adjudication D2 = delete + placeholder): the sealed-history door must tell
 * the reloaded panel which turns booked a credential screenshot whose
 * original pixels were purged, or the history shows nothing about an image
 * the user remembers sending. Zero mocks against the dev database, same
 * posture as the draft/goal folds:
 * <ul>
 * <li>a user message owning a frame with the credLedger marker reads back
 * {@code credLedger=true}, its neighbour answer stays false;</li>
 * <li>frames without the marker (plain attachments, older bookings) keep
 * an honest false — the fold never invents a placeholder.</li>
 * </ul>
 * Fixtures create random-tagged users/projects and clean up their session so
 * the shipped demo data stays untouched.
 */
@SpringBootTest(classes = CoreApplication.class)
class ChatCredentialMarkerReadProjectionTest {

    @Autowired
    private ChatFrameService frameService;

    @Autowired
    private ChatSessionService sessionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String tag;
    private long userId;
    private long projectId;
    private long sessionId;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        userId = jdbcTemplate.queryForObject("""
                        INSERT INTO tp_user (username, password_hash, display_name, role, status)
                        VALUES (?, '$2a$10$x', ?, 1, 1) RETURNING id
                        """, Long.class, tag, tag);
        projectId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, tag, tag);
    }

    @AfterEach
    void tearDown() {
        if (sessionId != 0L) {
            jdbcTemplate.update("DELETE FROM tp_ai_chat_draft WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM tp_ai_chat_frame WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM tp_ai_chat_message WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM tp_ai_chat_session WHERE id = ?", sessionId);
        }
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_user WHERE id = ?", userId);
    }

    /** One sealed user echo + sealed answer; returns the user-turn row id. */
    private long sealedConversation() {
        var session = sessionService.create(projectId, userId, "凭证截图读投影", 1, "{}");
        sessionId = session.id();
        long userTurn = frameService.openMessage(sessionId, "user", "这是登录态截图");
        frameService.closeMessage(userTurn, "done");
        long answerTurn = frameService.openMessage(sessionId, "assistant", "已解析");
        frameService.closeMessage(answerTurn, "done");
        return userTurn;
    }

    private void bookFrame(long messageId, String metaJson) {
        long seq = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) + 1 FROM tp_ai_chat_frame WHERE message_id = ?",
                Long.class, messageId);
        jdbcTemplate.update("INSERT INTO tp_ai_chat_frame (session_id, message_id, seq, event_type, payload, meta_json) "
                        + "VALUES (?, ?, ?, 'trace', '过程帧', ?::jsonb)",
                sessionId, messageId, seq, metaJson);
    }

    private ChatMessageInfo rowOf(List<ChatMessageInfo> rows, long messageId) {
        return rows.stream().filter(row -> row.id() == messageId).findFirst().orElseThrow();
    }

    @Test
    void bookedUserTurnReadsBackWithTheCredentialMarker() {
        long userTurn = sealedConversation();
        bookFrame(userTurn, "{\"" + CredentialScreenshotGovernance.META_CRED_KEY
                + "\":[\"object-key-1\"],\"chatImages\":[{\"objectKey\":\"object-key-1\"}]}");

        List<ChatMessageInfo> rows = frameService.listSealedMessages(userId, sessionId);

        assertTrue(rowOf(rows, userTurn).credLedger(),
                "a frame booking credLedger must fold onto its own message row");
        long answerTurn = rows.stream().map(ChatMessageInfo::id)
                .filter(id -> id != userTurn).findFirst().orElseThrow();
        assertFalse(rowOf(rows, answerTurn).credLedger(),
                "the neighbour answer carries no placeholder of its own");
    }

    @Test
    void framesWithoutTheMarkerKeepAnHonestFalse() {
        long userTurn = sealedConversation();
        bookFrame(userTurn, "{\"chatImages\":[{\"objectKey\":\"object-key-2\"}]}");

        List<ChatMessageInfo> rows = frameService.listSealedMessages(userId, sessionId);

        assertFalse(rowOf(rows, userTurn).credLedger(),
                "a plain attachment frame never reads as a credential booking");
    }
}
