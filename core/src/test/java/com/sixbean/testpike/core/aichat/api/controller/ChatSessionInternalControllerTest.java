package com.sixbean.testpike.core.aichat.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.aichat.ChatObjectRef;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.AiChatRunMessage;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.api.dto.ChatRunInfo;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.api.dto.CreateSessionRequest;
import com.sixbean.testpike.core.aichat.api.dto.StartRunRequest;
import com.sixbean.testpike.core.events.domain.component.RabbitEventConfig;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Internal dialog-session door contract .
 * <p>The door is what the admin SSE orchestration calls before streaming:
 * create a session, then start a run. What this pins on the real dev database
 * with zero mocks (same posture as ChatFrameInternalControllerTest):
 * <ul>
 * <li>session creation forwards to {@code ChatSessionService.create} unchanged —
 * the whitelist-context refusal stays a service rule, never re-implemented
 * here (thin door, no second validation copy);</li>
 * <li>start-run lands exactly the two ledger rows the run needs: the user
 * echo closed as done, the assistant placeholder opened as streaming —
 * the frame writer (ai-service, segment C) streams into that placeholder;</li>
 * <li>ownership is re-checked on the run path: a foreign user cannot enqueue
 * work into another user's conversation (AF-g horizontal red line);</li>
 * <li>the run request reaches RabbitMQ on route {@code ai.chat.run} of
 * {@code testpike.events} with the full identity payload (same posture
 * as AiGenPublisherTest — real broker, temporary auto-delete queue).</li>
 * </ul>
 * Fixtures create random-tagged users/projects so demo projects stay untouched.
 */
@SpringBootTest(classes = CoreApplication.class)
class ChatSessionInternalControllerTest {

    @Autowired
    private ChatSessionInternalController controller;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private MessageConverter caseEventMessageConverter;

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

    private String freshTag() {
        return "t426b" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /** Fresh session under a random user/project pair, created through the door itself. */
    private ChatSessionInfo freshSession() {
        String tag = freshTag();
        Response<ChatSessionInfo> created = controller.createSession(
                new CreateSessionRequest(newProjectId(tag), newUserId(tag), "Run door " + tag,
                        1, "{\"route\":\"/cases\"}"));
        return created.getData();
    }

    /**
 * a2 pinned-context read door: the dialog worker reads back
 * the whitelisted context of one owned session (same AF-g posture as the
 * run path — ownership single-sourced in requireOwned, no second copy).
 */
    @Test
    void contextDoorReadsBackTheWhitelistedContextForTheOwner() {
        String tag = freshTag();
        long owner = newUserId(tag);
        ChatSessionInfo info = controller.createSession(new CreateSessionRequest(
                newProjectId(tag), owner, "Pinned ctx " + tag, 1,
                "{\"route\":\"/executions\"}")).getData();
        try {
            Response<String> context = controller.sessionContext(info.id(), owner);
            assertTrue(context.getData().contains("\"route\""),
                    "the stored whitelist object comes back verbatim: " + context.getData());
            assertTrue(context.getData().contains("/executions"));
        } finally {
            jdbcTemplate.update("DELETE FROM tp_ai_chat_session WHERE id = ?", info.id());
        }
    }

    /** A stranger reading another user's pinned context is refused by name. */
    @Test
    void contextDoorRefusesAForeignUserWithTheOwnershipReason() {
        String tag = freshTag();
        ChatSessionInfo info = controller.createSession(new CreateSessionRequest(
                newProjectId(tag), newUserId(tag), "Pinned ctx " + tag, 1,
                "{\"route\":\"/cases\"}")).getData();
        try {
            ServiceException refusal = assertThrows(ServiceException.class,
                    () -> controller.sessionContext(info.id(), newUserId(tag + "x")));
            assertTrue(refusal.getMessage().contains("不属于当前用户"), refusal.getMessage());
        } finally {
            jdbcTemplate.update("DELETE FROM tp_ai_chat_session WHERE id = ?", info.id());
        }
    }

    @Test
    void createSessionDoorPersistsTheSessionRow() {
        String tag = freshTag();
        ChatSessionInfo info = controller.createSession(new CreateSessionRequest(
                newProjectId(tag), newUserId(tag), "Dock session " + tag, 2, "{\"route\":\"/mock\"}"))
                .getData();

        assertTrue(info.id() > 0);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT project_id, user_id, title, source, status, last_seq FROM tp_ai_chat_session WHERE id = ?",
                info.id());
        assertEquals((long) info.projectId(), ((Number) row.get("project_id")).longValue());
        assertEquals("Dock session " + tag, row.get("title"));
        assertEquals(2, ((Number) row.get("source")).intValue());
        assertEquals(0L, ((Number) row.get("last_seq")).longValue(),
                "a fresh session starts with an untouched frame cursor");
    }

    @Test
    void createSessionDoorRefusesANonObjectContextWithTheServiceReason() {
        String tag = freshTag();
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.createSession(
                new CreateSessionRequest(newProjectId(tag), newUserId(tag), "Bad context " + tag,
                        1, "[1,2,3]")));
        assertTrue(ex.getMessage().contains("JSON"), ex.getMessage());
    }

    /**
     * Page context may only carry navigation identifiers (route / project /
     * object / execution). Anything else — credentials, profile blobs, request
     * bodies — is refused by name so a leaked field is visible in the error.
     */
    @Test
    void createSessionDoorRefusesContextKeysOutsideTheIdentifierSet() {
        String tag = freshTag();
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.createSession(
                new CreateSessionRequest(newProjectId(tag), newUserId(tag), "Off whitelist " + tag,
                        1, "{\"route\":\"/cases\",\"authorization\":\"Bearer abc\"}")));
        assertTrue(ex.getMessage().contains("authorization"), ex.getMessage());
    }

    /**
 * Cross-project pointer guard: the context's projectId must equal the
 * session's own segment. A forged id of another project is refused before the
 * row is written (TC-AO-05 negative rail, horizontal-access red line).
 */
    @Test
    void createSessionDoorRefusesACrossProjectPointerInContext() {
        String tag = freshTag();
        long ownProject = newProjectId(tag);
        long otherProject = newProjectId(tag + "-x");
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.createSession(
                new CreateSessionRequest(ownProject, newUserId(tag), "Cross project " + tag,
                        1, "{\"route\":\"/cases\",\"projectId\":" + otherProject + "}")));
        assertTrue(ex.getMessage().contains("projectId"), ex.getMessage());
    }

    /** Values stay scalars: an object/array under a legal key is a smuggling channel. */
    @Test
    void createSessionDoorRefusesNonScalarContextValues() {
        String tag = freshTag();
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.createSession(
                new CreateSessionRequest(newProjectId(tag), newUserId(tag), "Nested value " + tag,
                        1, "{\"route\":{\"path\":\"/cases\",\"rows\":[1,2,3]}}")));
        assertTrue(ex.getMessage().contains("route"), ex.getMessage());
    }

    /** The legal four-key shape persists verbatim as the session's context_json. */
    @Test
    void createSessionDoorPersistsTheWhitelistedContext() {
        String tag = freshTag();
        long projectId = newProjectId(tag);
        ChatSessionInfo info = controller.createSession(new CreateSessionRequest(
                projectId, newUserId(tag), "Scoped session " + tag, 1,
                "{\"route\":\"/batch\",\"projectId\":" + projectId
                        + ",\"objectId\":\"77\",\"executionId\":45}")).getData();

        String stored = jdbcTemplate.queryForObject(
                "SELECT context_json::text FROM tp_ai_chat_session WHERE id = ?",
                String.class, info.id());
        assertTrue(stored.contains("/batch"), stored);
        assertTrue(stored.contains("executionId"), stored);
    }

    @Test
    void startRunOpensUserEchoClosedAndAssistantStreaming() {
        ChatSessionInfo session = freshSession();

        ChatRunInfo run = controller.startRun(session.id(),
                new StartRunRequest(session.userId(), "帮我评审登录用例", null, null)).getData();

        Map<String, Object> userRow = jdbcTemplate.queryForMap(
                "SELECT role, content, status FROM tp_ai_chat_message WHERE id = ?", run.userMessageId());
        assertEquals("user", userRow.get("role"));
        assertEquals("帮我评审登录用例", userRow.get("content"));
        assertEquals("done", userRow.get("status"),
                "the user echo is a finished turn, never left streaming");

        Map<String, Object> assistantRow = jdbcTemplate.queryForMap(
                "SELECT role, content, status FROM tp_ai_chat_message WHERE id = ?", run.assistantMessageId());
        assertEquals("assistant", assistantRow.get("role"));
        assertNull(assistantRow.get("content"), "the placeholder carries no invented answer");
        assertEquals("streaming", assistantRow.get("status"),
                "the placeholder is the window the frame writer streams into");
    }

    @Test
    void startRunRefusesAForeignUserWithANamedReason() {
        ChatSessionInfo session = freshSession();
        long stranger = newUserId(freshTag());

        ServiceException ex = assertThrows(ServiceException.class, () -> controller.startRun(
                session.id(), new StartRunRequest(stranger, "越权提问", null, null)));
        assertTrue(ex.getMessage().contains("不属于当前用户"), ex.getMessage());

        List<String> roles = jdbcTemplate.queryForList(
                "SELECT role FROM tp_ai_chat_message WHERE session_id = ?", String.class, session.id());
        assertTrue(roles.isEmpty(), "a refused run must not leave ledger rows behind");
    }

    @Test
    void startRunRefusesABlankPrompt() {
        ChatSessionInfo session = freshSession();

        ServiceException ex = assertThrows(ServiceException.class, () -> controller.startRun(
                session.id(), new StartRunRequest(session.userId(), "   ", null, null)));
        assertTrue(ex.getMessage().contains("问题"), ex.getMessage());
    }

    /**
     * Knife 4b rail: a chip outside the adjudicated kinds (or over the cap)
     * refuses the run BEFORE any ledger row lands — the user's pinned
     * reference list must never half-apply.
     */
    @Test
    void startRunRefusesAnUnsupportedReferenceKindWithoutLedgerRows() {
        ChatSessionInfo session = freshSession();

        ServiceException ex = assertThrows(ServiceException.class, () -> controller.startRun(
                session.id(), new StartRunRequest(session.userId(), "引用问题",
                        List.of(new ChatObjectRef("secret", 1L, "非法类型")), null)));
        assertTrue(ex.getMessage().contains("secret"), ex.getMessage());

        List<String> roles = jdbcTemplate.queryForList(
                "SELECT role FROM tp_ai_chat_message WHERE session_id = ?", String.class, session.id());
        assertTrue(roles.isEmpty(), "a refused reference must not leave a half-open turn behind");
    }

    @Test
    void startRunPublishesTheRunMessageOnTheEventsExchange() throws Exception {
        ChatSessionInfo session = freshSession();
        String queueName = "test.ai-chat-run." + UUID.randomUUID().toString().substring(0, 8);
        amqpAdmin.declareQueue(new Queue(queueName, false, true, true));
        amqpAdmin.declareBinding(new Binding(queueName, Binding.DestinationType.QUEUE,
                RabbitEventConfig.EXCHANGE, AiChatRunMessage.ROUTING_KEY, null));
        try {
            ChatRunInfo run = controller.startRun(session.id(),
                    new StartRunRequest(session.userId(), "统计本周失败率",
                            List.of(new ChatObjectRef("case", 7L, " 登录用例 "),
                                    new ChatObjectRef("case", 7L, "重复项")), null)).getData();

            var received = rabbitTemplate.receive(queueName, 5000);
            assertNotNull(received, "the run request must reach the broker on ai.chat.run");
            AiChatRunMessage msg = (AiChatRunMessage) caseEventMessageConverter.fromMessage(received);
            assertEquals(session.id(), msg.sessionId());
            assertEquals(run.assistantMessageId(), msg.assistantMessageId());
            assertEquals(session.projectId(), msg.projectId());
            assertEquals(session.userId(), msg.userId());
            assertEquals("统计本周失败率", msg.prompt());
            // Knife 4b: chips ride the run message already canonicalised —
            // deduplicated by (kind,id) with the first arrival's trimmed title.
            assertEquals(1, msg.refObjects().size(), msg.refObjects().toString());
            assertEquals("case", msg.refObjects().get(0).kind());
            assertEquals(7L, msg.refObjects().get(0).id());
            assertEquals("登录用例", msg.refObjects().get(0).title());
            // Knife 1 : a plain session pins no execution.
            assertNull(msg.executionId());
        } finally {
            amqpAdmin.deleteQueue(queueName);
        }
    }

    /**
 * Knife 1 : a badge-deep-link session carries an executionId in its
 * whitelist context; opening a run must fold that pointer into the broker
 * message so the ai-service side can fetch the failure diagnosis. The
 * ledger door is the single extraction point (no second copy client-side).
 */
    @Test
    void startRunFoldsTheSessionExecutionContextIntoTheRunMessage() throws Exception {
        String tag = freshTag();
        long projectId = newProjectId(tag);
        ChatSessionInfo session = controller.createSession(new CreateSessionRequest(
                projectId, newUserId(tag), "Badge session " + tag, 3,
                "{\"route\":\"/execution\",\"projectId\":" + projectId
                        + ",\"executionId\":909}")).getData();

        String queueName = "test.ai-chat-run." + UUID.randomUUID().toString().substring(0, 8);
        amqpAdmin.declareQueue(new Queue(queueName, false, true, true));
        amqpAdmin.declareBinding(new Binding(queueName, Binding.DestinationType.QUEUE,
                RabbitEventConfig.EXCHANGE, AiChatRunMessage.ROUTING_KEY, null));
        try {
            controller.startRun(session.id(),
                    new StartRunRequest(session.userId(), "这次执行为什么失败", null, null));

            var received = rabbitTemplate.receive(queueName, 5000);
            assertNotNull(received, "the badge run must reach the broker too");
            AiChatRunMessage msg = (AiChatRunMessage) caseEventMessageConverter.fromMessage(received);
            assertEquals(909L, msg.executionId(), "the execution pointer crosses the MQ boundary");
        } finally {
            amqpAdmin.deleteQueue(queueName);
        }
    }

    // ---------- undo door ----------

    /**
     * The undo door re-checks ownership before the undo service is touched
     * (AF-g posture: requireOwned is the single source of the refusal). A
     * stranger calling it must neither get a draft id nor leave a pending
     * CASE_ROLLBACK card in the ledger.
     */
    @Test
    void undoDoorRefusesAStrangerBeforeAnyCardLands() {
        String tag = freshTag();
        ChatSessionInfo session = freshSession();
        long stranger = newUserId(tag + "-x");

        try {
            ServiceException refusal = assertThrows(ServiceException.class,
                    () -> controller.undoSession(session.id(), stranger));
            assertTrue(refusal.getMessage().contains("不属于当前用户"), refusal.getMessage());

            Integer cards = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM tp_ai_chat_draft WHERE session_id = ?", Integer.class,
                    session.id());
            assertEquals(0, cards == null ? -1 : cards,
                    "a refused undo must not leave a half-landed card behind");
        } finally {
            jdbcTemplate.update("DELETE FROM tp_ai_chat_session WHERE id = ?", session.id());
        }
    }

    /**
     * The owner reaching the undo door lands on the real UndoCaseDraftService:
     * a fresh session has no applied case write, so the service's own loud
     * refusal comes back through the door — this pins the wiring (the door
     * actually calls the service) without mocking anything.
     */
    @Test
    void undoDoorReachesTheUndoServiceForTheOwner() {
        ChatSessionInfo session = freshSession();
        try {
            ServiceException refusal = assertThrows(ServiceException.class,
                    () -> controller.undoSession(session.id(), session.userId()));
            assertTrue(refusal.getMessage().contains("还没有已接受的用例写入"),
                    "the owner must hear the undo service's own sentence: " + refusal.getMessage());
        } finally {
            jdbcTemplate.update("DELETE FROM tp_ai_chat_session WHERE id = ?", session.id());
        }
    }
}
