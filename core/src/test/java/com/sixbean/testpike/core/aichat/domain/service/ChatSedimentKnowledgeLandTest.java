package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.AiKbIngestMessage;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.api.dto.ChatSedimentView;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.events.domain.component.RabbitEventConfig;
import com.sixbean.testpike.core.events.domain.service.AiGenPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One-click "save conversation as knowledge" landing (E5 sedimentation, AO
 * batch phase 2b): the exported Markdown — already masked and rebuilt by
 * the phase-2a single source — lands as a real tp_document row through the
 * shared upload pipeline (TEST_SPEC default category; the REFERENCE category
 * belongs to a later batch and is deliberately not written here), and the
 * knowledge-base ingest event goes out on the same ai.kb.ingest routing key
 * every other ingest trigger uses. Re-saving the same minute must fail with a
 * message that tells the user what happened and what to do, never a silent
 * second copy or a swallowed error.
 * <p>Real dev database and real RabbitMQ, zero mocks (same posture as
 * KbIngestPublisherTest); rows are cleaned up in finally with one-shot tags so
 * the demo projects stay untouched.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {
        "testpike.scheduling.enabled=false"})
class ChatSedimentKnowledgeLandTest {

    @Autowired
    private ChatSessionService sessionService;

    @Autowired
    private ChatFrameService frameService;

    @Autowired
    private ChatSedimentService sedimentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private MessageConverter caseEventMessageConverter;

    private final List<Long> createdDocIds = new ArrayList<>();

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

    /** Session with one sealed question turn — enough content to land a document. */
    private ChatSessionInfo sessionWithDialog(String title) {
        String tag = "t435s" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        ChatSessionInfo session = sessionService.create(newProjectId(tag), newUserId(tag), title, 1, "{}");
        long messageId = frameService.openMessage(session.id(), "user", "为什么登录接口返回 401？");
        frameService.closeMessage(messageId, "done");
        long answer = frameService.openMessage(session.id(), "assistant", null);
        frameService.appendFrame(session.id(), answer,
                com.sixbean.testpike.core.aichat.domain.component.ChatFrameType.DELTA,
                "因为令牌过期，重新登录即可。", "{}");
        frameService.closeMessage(answer, "done");
        return session;
    }

    private Queue declareTempQueue(String routingKey) {
        Queue queue = new Queue("tp.test." + UUID.randomUUID(), false, true, true);
        amqpAdmin.declareQueue(queue);
        amqpAdmin.declareBinding(new Binding(queue.getName(), Binding.DestinationType.QUEUE,
                RabbitEventConfig.EXCHANGE, routingKey, null));
        return queue;
    }

    private AiKbIngestMessage awaitMessage(Queue queue) {
        Message msg = rabbitTemplate.receive(queue.getName(), 5000);
        return msg == null ? null : (AiKbIngestMessage) caseEventMessageConverter.fromMessage(msg);
    }

    private void trackDocuments(long projectId) {
        createdDocIds.addAll(jdbcTemplate.queryForList(
                "SELECT id FROM tp_document WHERE project_id = ?", Long.class, projectId));
    }

    private void cleanup() {
        for (Long docId : createdDocIds) {
            jdbcTemplate.update("DELETE FROM tp_document_chunk WHERE document_id = ?", docId);
            jdbcTemplate.update("DELETE FROM tp_document WHERE id = ?", docId);
        }
        createdDocIds.clear();
    }

    @Test
    void landWritesDocumentRowAndPublishesKbIngest() {
        Queue queue = declareTempQueue(AiGenPublisher.ROUTING_KB_INGEST);
        ChatSessionInfo session = sessionWithDialog("存知识试验");
        try {
            ChatSedimentView view = sedimentService.landAsKnowledge(session.userId(), session.id());

            assertNotNull(view.documentId(), "landing returns the created document id");
            trackDocuments(session.projectId());
            assertTrue(view.documentName().startsWith("存知识试验"),
                    "document display name derives from the export file name, got: " + view.documentName());
            assertTrue(view.chunkCount() >= 1, "sync parsing produced chunks, got: " + view.chunkCount());

            Integer status = jdbcTemplate.queryForObject(
                    "SELECT status FROM tp_document WHERE id = ?", Integer.class, view.documentId());
            String category = jdbcTemplate.queryForObject(
                    "SELECT doc_category FROM tp_document WHERE id = ?", String.class, view.documentId());
            assertEquals(1, status, "document reached the success state (1) after the sync pipeline");
            assertEquals("TEST_SPEC", category, "dialog sediment lands under the default TEST_SPEC category");

            AiKbIngestMessage evt = awaitMessage(queue);
            assertNotNull(evt, "ai.kb.ingest must really arrive so ai-service vectorizes the document");
            assertEquals(view.documentId(), evt.documentId(), "ingest payload names the landed document");
            assertEquals(session.projectId(), evt.projectId(), "project id travels through (H1)");
            assertNotNull(evt.occurredAt(), "occurredAt feeds the consumer idempotency key");
        } finally {
            cleanup();
        }
    }

    @Test
    void replayingTheSameMinuteNamesTheConflictInsteadOfSilentlySwallowing() {
        ChatSessionInfo session = sessionWithDialog("重放试验");
        try {
            ChatSedimentView first = sedimentService.landAsKnowledge(session.userId(), session.id());
            trackDocuments(session.projectId());
            try {
                ChatSedimentView second = sedimentService.landAsKnowledge(session.userId(), session.id());
                // The minute stamp rolled over between the two calls: the second
                // landing is a legitimate new snapshot, its name must say so.
                assertNotEquals(first.documentName(), second.documentName(),
                        "a successful re-land means the minute stamp moved — names must differ");
            } catch (ServiceException e) {
                assertEquals(BizErrorCode.RESOURCE_CONFLICT, e.getCode(),
                        "same-minute re-save reports the duplicate, not a raw internal error");
                assertTrue(e.getMessage().contains(first.documentName()),
                        "the refusal names what already exists: " + e.getMessage());
                // R63: the duplicate refusal must also carry an action word and
                // the literal button the dialog toolbar actually renders.
                assertTrue(e.getMessage().contains("请"),
                        "the refusal says what to do next: " + e.getMessage());
                assertTrue(e.getMessage().contains("「把本对话存为知识文档，供检索与生成参考」"),
                        "the refusal quotes the real button tooltip as the re-entry: "
                                + e.getMessage());
            }
        } finally {
            cleanup();
        }
    }

    @Test
    void strangerCannotLandAnotherUsersConversation() {
        ChatSessionInfo session = sessionWithDialog("别人的会话");
        long strangerId = newUserId("t435s-stranger-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        ServiceException e = assertThrows(ServiceException.class,
                () -> sedimentService.landAsKnowledge(strangerId, session.id()),
                "a foreign reader cannot push another user's dialog into the knowledge base");
        assertEquals(BizErrorCode.FORBIDDEN, e.getCode());
    }

    @Test
    void emptyDialogIsRefusedBeforeAnyDocumentRowExists() {
        String tag = "t435s" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        ChatSessionInfo session = sessionService.create(newProjectId(tag), newUserId(tag), "空对话", 1, "{}");
        // Only plumbing rows on the ledger — no question/answer turn at all.
        long sys = frameService.openMessage(session.id(), "system", "内部路由备注");
        frameService.closeMessage(sys, "done");
        try {
            ServiceException e = assertThrows(ServiceException.class,
                    () -> sedimentService.landAsKnowledge(session.userId(), session.id()),
                    "a dialog without Q&A turns must not create a header-only shell document");
            assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
            assertTrue(e.getMessage().contains("还没有问答内容"),
                    "the refusal tells the user what is missing: " + e.getMessage());
            // R63: missing Q&A content is only half the answer — the refusal
            // must say what to do and name the real toolbar button literal.
            assertTrue(e.getMessage().contains("请"),
                    "the refusal carries an action word: " + e.getMessage());
            assertTrue(e.getMessage().contains("「把本对话存为知识文档，供检索与生成参考」"),
                    "the refusal quotes the button the user will press after chatting: "
                            + e.getMessage());
            Integer rows = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM tp_document WHERE project_id = ?", Integer.class,
                    session.projectId());
            assertEquals(0, rows, "the guard fires before any document row is written");
        } finally {
            trackDocuments(session.projectId());
            cleanup();
        }
    }
}
