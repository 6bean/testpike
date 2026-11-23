package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.domain.component.ChatFrameType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The frame door's behaviour when the session it is asked to number is gone.
 * <p>Every persisted frame takes its sequence out of the session row, so an
 * unknown session id used to end as "no rows returned from the locked read" —
 * a framework-level empty result the global response handler folds into the
 * generic internal code, whose only advice on screen is to try again later.
 * Retrying cannot succeed, so the caller is left waiting on an answer that is
 * never coming: an error state has to say what is missing and where to fix it.
 * <p>Pinned here against the real dev database with zero mocks (same posture as
 * {@code ChatFrameProtocolTest}):
 * <ul>
 *   <li>an absent session is refused as a not-found business error that names
 *       the conversation and the way back to it (the history panel / a new
 *       conversation), and never the "busy, retry later" wording;</li>
 *   <li>the refusal writes nothing — no frame row, no message row;</li>
 *   <li>a live session keeps the adjudicated sequence discipline exactly as
 *       before: monotonic per-session numbering, cursor moved by one per frame.</li>
 * </ul>
 */
@SpringBootTest(classes = CoreApplication.class)
class ChatFrameSessionAbsenceRefusalTest {

    @Autowired
    private ChatFrameService frameService;

    @Autowired
    private ChatSessionService sessionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ChatSessionInfo freshSession() {
        String t = ChatProjectionTestSupport.randomTag(jdbcTemplate, "framegone");
        return sessionService.create(
                ChatProjectionTestSupport.insertProject(jdbcTemplate, t),
                ChatProjectionTestSupport.insertUser(jdbcTemplate, t),
                "帧门会话缺席", 1, "{}");
    }

    /** A session id no row can answer to — the state after a deletion or a bad cursor. */
    private long absentSessionId() {
        Long max = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM tp_ai_chat_session", Long.class);
        return (max == null ? 0L : max) + 777L;
    }

    @Test
    void absentSessionIsRefusedWithAnActionableReason() {
        long gone = absentSessionId();

        ServiceException refusal = assertThrows(ServiceException.class,
                () -> frameService.appendFrame(gone, 1L, ChatFrameType.STATE, "会话已不在", null),
                "an unknown session must raise a business refusal the caller can act on");

        assertEquals(BizErrorCode.NOT_FOUND, refusal.getCode(),
                "a missing conversation is a not-found answer, never an internal code");
        String message = refusal.getMessage();
        assertNotNull(message);
        assertTrue(message.contains("对话会话"), "the refusal must name what is missing: " + message);
        assertTrue(message.contains("「历史对话」"), "the refusal must give the entry path to reopen a conversation: " + message);
        assertTrue(message.contains("「新建对话」"), "the refusal must give the fallback entry when the list is empty: " + message);
        assertFalse(message.contains("系统繁忙"), "the generic 'busy, retry later' wording is a dead end: " + message);
    }

    @Test
    void absentSessionRefusalWritesNothingToTheLedger() {
        long gone = absentSessionId();

        assertThrows(ServiceException.class,
                () -> frameService.appendFrame(gone, 1L, ChatFrameType.DELTA, "不该留下的正文", null));

        Integer frames = jdbcTemplate.queryForObject(
                "SELECT count(1) FROM tp_ai_chat_frame WHERE session_id = ?", Integer.class, gone);
        assertEquals(0, frames, "a refused frame never reaches the ledger");
    }

    @Test
    void liveSessionKeepsTheMonotonicSequenceDiscipline() {
        ChatSessionInfo session = freshSession();
        long message = frameService.openMessage(session.id(), "assistant", null);

        long first = frameService.appendFrame(session.id(), message, ChatFrameType.STATE, "已收到", null);
        long second = frameService.appendFrame(session.id(), message, ChatFrameType.DELTA, "正文增量", null);

        assertTrue(second > first, "the healthy path still numbers frames monotonically");
        assertEquals(second, frameService.lastSeq(session.id()),
                "the session cursor follows the last allocated seq");

        Integer frames = jdbcTemplate.queryForObject(
                "SELECT count(1) FROM tp_ai_chat_frame WHERE session_id = ?", Integer.class, session.id());
        assertEquals(2, frames, "both frames landed on a live session");
    }
}
