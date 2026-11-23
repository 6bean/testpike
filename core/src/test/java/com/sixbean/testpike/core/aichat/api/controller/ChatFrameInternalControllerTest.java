package com.sixbean.testpike.core.aichat.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.api.dto.AppendFrameRequest;
import com.sixbean.testpike.core.aichat.api.dto.ChatFrame;
import com.sixbean.testpike.core.aichat.api.dto.ChatMessageInfo;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.api.dto.CloseMessageRequest;
import com.sixbean.testpike.core.aichat.api.dto.OpenMessageRequest;
import com.sixbean.testpike.core.aichat.domain.service.ChatFrameService;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Internal frame-ledger endpoints contract .
 * <p>The controller is the HTTP door the ai-service gateway writes frames through
 * and the admin SSE orchestration replays through. What this pins, on the real
 * dev database with zero mocks (same posture as ChatFrameProtocolTest):
 * <ul>
 * <li>frame type arrives as the wire-name <em>string</em> (the SSE literal), and
 * an unknown name is refused with a human-readable reason that names the
 * legal values — never a raw enum crash;</li>
 * <li>open/append/last-seq/close/abort forward to the ledger service unchanged
 * (thin controller: no second state machine here);</li>
 * <li>replay carries the requesting userId and is ownership-checked — a
 * foreign user is refused another user's conversation (horizontal access);</li>
 * <li>abort keeps the partial answer exactly as the ledger service defines it.</li>
 * </ul>
 * Fixtures create random-tag users/projects so demo projects stay untouched.
 */
@SpringBootTest(classes = CoreApplication.class)
class ChatFrameInternalControllerTest {

    @Autowired
    private ChatFrameInternalController controller;

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

    /** Fresh session with its own user/project pair under a random tag. */
    private ChatSessionInfo freshSession() {
        String tag = "t426c" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return sessionService.create(newProjectId(tag), newUserId(tag), "Frame gateway " + tag,
                1, "{\"route\":\"/cases\"}");
    }

    @Test
    void openMessageEndpointStartsTheStreamingTurn() {
        ChatSessionInfo session = freshSession();

        Response<Long> response = controller.openMessage(session.id(),
                new OpenMessageRequest("assistant", null));

        long messageId = response.getData();
        assertTrue(messageId > 0);
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_ai_chat_message WHERE id = ?", String.class, messageId);
        assertEquals("streaming", status, "endpoint opened a real streaming turn in the ledger");
    }

    @Test
    void appendFrameAcceptsTheWireNameStringAndAllocatesTheLedgerSeq() {
        ChatSessionInfo session = freshSession();
        long message = frameService.openMessage(session.id(), "assistant", null);

        long seq = controller.appendFrame(session.id(),
                new AppendFrameRequest(message, "state", "已收到，正在路由模型", null)).getData();
        assertTrue(seq > 0, "wire name 'state' maps onto the ledger frame type");

        long lastSeq = controller.lastSeq(session.id()).getData();
        assertEquals(seq, lastSeq, "last-seq endpoint reports the same session cursor");
    }

    @Test
    void appendFrameRefusesAnUnknownWireNameWithAReadableReason() {
        ChatSessionInfo session = freshSession();
        long message = frameService.openMessage(session.id(), "assistant", null);

        ServiceException ex = assertThrows(ServiceException.class, () -> controller.appendFrame(
                session.id(), new AppendFrameRequest(message, "not-a-frame", "x", null)));
        assertTrue(ex.getMessage().contains("not-a-frame"), ex.getMessage());
        assertTrue(ex.getMessage().contains("state"),
                "refusal must name the legal values, got: " + ex.getMessage());
    }

    @Test
    void appendFrameRefusesTheTransportOnlyHeartbeat() {
        ChatSessionInfo session = freshSession();
        long message = frameService.openMessage(session.id(), "assistant", null);
        long before = frameService.lastSeq(session.id());

        assertThrows(ServiceException.class, () -> controller.appendFrame(
                session.id(), new AppendFrameRequest(message, "heartbeat", null, null)));

        assertEquals(before, frameService.lastSeq(session.id()),
                "a refused heartbeat must not consume a session seq");
    }

    @Test
    void replayResendsOnlyTheUnacknowledgedTailOfTheStreamingBlock() {
        ChatSessionInfo session = freshSession();
        long message = frameService.openMessage(session.id(), "assistant", null);
        controller.appendFrame(session.id(), new AppendFrameRequest(message, "state", "收单", null));
        long stateSeq = frameService.lastSeq(session.id());
        controller.appendFrame(session.id(), new AppendFrameRequest(message, "reason", "分析中", null));
        controller.appendFrame(session.id(), new AppendFrameRequest(message, "delta", "半截正文", null));

        List<ChatFrame> replay = controller.replay(session.id(), stateSeq, session.userId()).getData();

        assertEquals(2, replay.size(), "frames after the cursor only");
        assertEquals("reason", replay.get(0).eventType().wireName());
        assertEquals("delta", replay.get(1).eventType().wireName());
    }

    @Test
    void replayOfAForeignUserIsRefusedWithANamedReason() {
        ChatSessionInfo session = freshSession();
        long stranger = newUserId("t426s" + UUID.randomUUID().toString().substring(0, 8));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.replay(session.id(), 0L, stranger));
        assertTrue(ex.getMessage().contains("不属于当前用户"), ex.getMessage());
    }

    @Test
    void closeEndpointSealsTheMessageInTheLedger() {
        ChatSessionInfo session = freshSession();
        long message = frameService.openMessage(session.id(), "assistant", null);
        controller.appendFrame(session.id(), new AppendFrameRequest(message, "done", "done",
                "{\"total_tokens\":12}"));

        ChatMessageInfo info = controller.closeMessage(message, new CloseMessageRequest("done")).getData();

        assertEquals("done", info.status());
        assertTrue(info.seqTo() != null && info.seqTo() >= info.seqFrom(),
                "frame window sealed on close");
    }

    @Test
    void listMessagesReturnsSealedTurnsInOrderWithTheRebuiltAnswerBody() {
        ChatSessionInfo session = freshSession();

        long user = controller.openMessage(session.id(), new OpenMessageRequest("user", "回归批次怎么查")).getData();
        frameService.closeMessage(user, "done");
        long assistant = controller.openMessage(session.id(), new OpenMessageRequest("assistant", null)).getData();
        controller.appendFrame(session.id(), new AppendFrameRequest(assistant, "reason", "先查批次表", null));
        controller.appendFrame(session.id(), new AppendFrameRequest(assistant, "delta", "最近三批", null));
        controller.appendFrame(session.id(), new AppendFrameRequest(assistant, "delta", "全部通过", null));
        // The done frame carries the usage numbers the live view showed (E9 attribution);
        // the message row itself keeps zeros until the run writes them back.
        controller.appendFrame(session.id(), new AppendFrameRequest(assistant, "done",
                "{\"messageId\":" + assistant + ",\"promptTokens\":121,\"completionTokens\":2008}", null));
        controller.closeMessage(assistant, new CloseMessageRequest("done"));

        List<ChatMessageInfo> messages =
                controller.listMessages(session.id(), session.userId()).getData();

        assertEquals(2, messages.size(), "both sealed turns come back");
        assertEquals("user", messages.get(0).role(), "oldest turn first");
        assertEquals("回归批次怎么查", messages.get(0).content(), "user echo body is stored verbatim");
        assertEquals("最近三批全部通过", messages.get(1).content(),
                "an assistant turn whose body lives in delta frames must be rebuilt from the ledger");
        assertEquals("done", messages.get(1).status());
        assertEquals(121, messages.get(1).promptTokens(),
                "usage numbers come from the done frame of the ledger (same rebuild posture as the body)");
        assertEquals(2008, messages.get(1).completionTokens(), "completion usage rebuilt too");
        assertNotNull(messages.get(0).createdAt(),
                "the ledger instant rides the view — the dialog day divider folds it (T463)");
    }

    @Test
    void listMessagesOfAForeignUserIsRefusedWithANamedReason() {
        ChatSessionInfo session = freshSession();
        long stranger = newUserId("t426m" + UUID.randomUUID().toString().substring(0, 8));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.listMessages(session.id(), stranger));
        assertTrue(ex.getMessage().contains("不属于当前用户"), ex.getMessage());
    }

    @Test
    void abortEndpointKeepsThePartialBodyTheUserWatched() {
        ChatSessionInfo session = freshSession();
        long message = frameService.openMessage(session.id(), "assistant", null);
        controller.appendFrame(session.id(), new AppendFrameRequest(message, "delta", "已生成的部分正文", null));

        ChatMessageInfo aborted = controller.abort(message).getData();

        assertEquals("aborted", aborted.status());
        assertEquals("已生成的部分正文", aborted.content(),
                "the endpoint must not discard generated output on abort");
    }
}
