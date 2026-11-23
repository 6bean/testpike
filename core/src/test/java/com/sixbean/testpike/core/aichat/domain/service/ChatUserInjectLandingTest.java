package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.api.dto.ChatFrame;
import com.sixbean.testpike.core.aichat.api.dto.InjectLanding;
import com.sixbean.testpike.core.aichat.domain.component.ChatFrameType;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mid-turn steering intake door : what happens
 * when a user submits a supplement while an answer is still streaming.
 * <p>Pinned on the real dev database with zero mocks (same posture as
 * {@code ChatMessageFeedbackTest}):
 * <ul>
 * <li>an injection on a running turn lands as a durable user-inject frame
 * bound to that turn - the frame is the acceptance proof, reload/replay
 * shows it exactly as delivered;</li>
 * <li>honest degradation: with no streaming turn the landing is null and
 * nothing is journalled (the caller then sends the text through the
 * regular message channel instead of dropping it);</li>
 * <li>a sealed (done) turn is not running - same null landing;</li>
 * <li>the worker's read door returns only user-inject frames above its
 * cursor, oldest first - answer frames stay on the replay door;</li>
 * <li>blank text is refused fail-closed with a named reason;</li>
 * <li>the horizontal red line: a stranger cannot inject into another user's
 * session and the ledger stays untouched.</li>
 * </ul>
 */
@SpringBootTest(classes = CoreApplication.class)
class ChatUserInjectLandingTest {

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

    /** Owned session with an assistant turn still streaming. */
    private long[] openRunningTurn() {
        String user = tag("inj-u");
        long userId = newUserId(user);
        long projectId = newProjectId(tag("inj-p"));
        ChatSessionInfo session = sessionService.create(projectId, userId, "执行中转向", 1, "{}");
        long messageId = frameService.openMessage(session.id(), "assistant", "运行中的回答");
        return new long[]{userId, session.id(), messageId};
    }

    @Test
    void injectionOnRunningTurnLandsTheDurableFrame() {
        long[] turn = openRunningTurn();
        InjectLanding landing = frameService.injectUserMessage(turn[1], turn[0], "  请把断言改成中文  ");
        assertNotNull(landing, "a running turn must accept the steering text");
        assertEquals(turn[2], landing.messageId(), "the injection joins the streaming turn");
        assertTrue(landing.seq() > 0, "the landed frame carries its allocated session seq");
        List<String> stored = jdbcTemplate.queryForList(
                "SELECT payload FROM tp_ai_chat_frame WHERE message_id = ? AND event_type = 'user-inject'",
                String.class, turn[2]);
        assertEquals(1, stored.size(), "exactly one user-inject row is journalled");
        assertEquals("请把断言改成中文", stored.get(0),
                "the supplement is stored stripped - never silently dropped or padded");
    }

    @Test
    void injectionWithoutARunningTurnDegradesHonestly() {
        String user = tag("inj-o");
        long userId = newUserId(user);
        long projectId = newProjectId(tag("inj-op"));
        ChatSessionInfo session = sessionService.create(projectId, userId, "无活跃轮", 1, "{}");
        long before = frameService.lastSeq(session.id());
        assertNull(frameService.injectUserMessage(session.id(), userId, "随便补充点什么"),
                "no streaming turn means no injection target - the caller degrades to a regular message");
        long after = frameService.lastSeq(session.id());
        assertEquals(before, after, "a degraded injection must not consume a seq");
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_ai_chat_frame WHERE session_id = ? AND event_type = 'user-inject'",
                Integer.class, session.id());
        assertEquals(0, rows, "nothing may be journalled for a turn that does not exist");
    }

    @Test
    void sealedTurnNeverReceivesAnInjection() {
        long[] turn = openRunningTurn();
        frameService.closeMessage(turn[2], "done");
        assertNull(frameService.injectUserMessage(turn[1], turn[0], "迟到的补充"),
                "a sealed turn is not running - the text must degrade, not leak into a closed window");
    }

    @Test
    void pendingReadsReturnOnlyUserInjectsInSeqOrder() {
        long[] turn = openRunningTurn();
        InjectLanding first = frameService.injectUserMessage(turn[1], turn[0], "先说的补充");
        frameService.appendFrame(turn[1], turn[2], ChatFrameType.DELTA, "回答片段", null);
        InjectLanding second = frameService.injectUserMessage(turn[1], turn[0], "后说的补充");
        List<ChatFrame> all = frameService.findInjectsAfter(turn[1], 0);
        assertEquals(2, all.size(), "the worker sees both injections and nothing else");
        assertEquals("先说的补充", all.get(0).payload());
        assertEquals("后说的补充", all.get(1).payload(), "oldest first - the worker consumes in send order");
        List<ChatFrame> afterFirst = frameService.findInjectsAfter(turn[1], first.seq());
        assertEquals(1, afterFirst.size(), "the cursor hides what the worker already consumed");
        assertEquals(second.seq(), afterFirst.get(0).seq());
    }

    @Test
    void blankInjectionIsRefusedWithANamedReason() {
        long[] turn = openRunningTurn();
        ServiceException ex = assertThrows(ServiceException.class,
                () -> frameService.injectUserMessage(turn[1], turn[0], "   "));
        assertTrue(ex.getMessage().contains("不能为空"),
                "the refusal must name the violated rule: " + ex.getMessage());
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_ai_chat_frame WHERE message_id = ? AND event_type = 'user-inject'",
                Integer.class, turn[2]);
        assertEquals(0, rows, "a refused injection leaves no ledger row");
    }

    @Test
    void foreignUserCannotInjectIntoAnotherUsersSession() {
        long[] turn = openRunningTurn();
        long strangerId = newUserId(tag("inj-x"));
        assertThrows(ServiceException.class,
                () -> frameService.injectUserMessage(turn[1], strangerId, "越权注入"));
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_ai_chat_frame WHERE message_id = ? AND event_type = 'user-inject'",
                Integer.class, turn[2]);
        assertEquals(0, rows, "the horizontal red line: a stranger's injection never lands");
    }
}
