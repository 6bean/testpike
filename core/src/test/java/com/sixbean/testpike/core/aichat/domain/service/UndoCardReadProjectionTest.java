package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.api.dto.ChatDraftInfo;
import com.sixbean.testpike.core.aichat.api.dto.ChatMessageInfo;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Undo-card read visibility : pressing undo files a
 * pending CASE_ROLLBACK card, and a card nobody can see can never be
 * confirmed — so the undo card must ride back through the sealed-history
 * door like every other confirmable card. This pins the chosen anchoring
 * ( decision, option A): the ledger row joins the session's newest
 * sealed assistant message, the same fold {@code foldDrafts} already serves,
 * with zero new read endpoints and no second card UI.
 * <p>Real dev database, no mocks (same posture as
 * {@code ChatDraftReadProjectionTest}). Fixtures create random-tagged
 * users/projects and clean up only their own rows by id — shipped baseline
 * tables are never truncated (R37).</p>
 */
@SpringBootTest(classes = CoreApplication.class)
class UndoCardReadProjectionTest {

    @Autowired
    private ChatFrameService frameService;

    @Autowired
    private ChatSessionService sessionService;

    @Autowired
    private UndoCaseDraftService undoService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdCaseIds = new ArrayList<>();
    private Long createdSessionId;
    private Long createdUserId;
    private Long createdProjectId;

    @AfterEach
    void removeOwnFixtureRows() {
        if (createdSessionId != null) {
            jdbcTemplate.update("DELETE FROM tp_ai_chat_draft WHERE session_id = ?",
                    createdSessionId);
            jdbcTemplate.update("DELETE FROM tp_ai_chat_frame WHERE message_id IN "
                    + "(SELECT id FROM tp_ai_chat_message WHERE session_id = ?)", createdSessionId);
            jdbcTemplate.update("DELETE FROM tp_ai_chat_message WHERE session_id = ?",
                    createdSessionId);
            jdbcTemplate.update("DELETE FROM tp_ai_chat_session WHERE id = ?", createdSessionId);
        }
        for (Long caseId : createdCaseIds) {
            jdbcTemplate.update("DELETE FROM tp_case_version WHERE case_id = ?", caseId);
            jdbcTemplate.update("DELETE FROM tp_case_step WHERE case_id = ?", caseId);
            jdbcTemplate.update("DELETE FROM tp_test_case WHERE id = ?", caseId);
        }
        if (createdProjectId != null) {
            jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", createdProjectId);
        }
        if (createdUserId != null) {
            jdbcTemplate.update("DELETE FROM tp_user WHERE id = ?", createdUserId);
        }
    }

    @Test
    void undoCardRidesBackOnTheNewestSealedAssistantRowAfterReload() throws Exception {
        createdUserId = ChatProjectionTestSupport.insertUser(jdbcTemplate,
                ChatProjectionTestSupport.randomTag(jdbcTemplate, "ucfu"));
        createdProjectId = ChatProjectionTestSupport.insertProject(jdbcTemplate,
                ChatProjectionTestSupport.randomTag(jdbcTemplate, "ucfp"));
        ChatSessionInfo session = sessionService.create(createdProjectId, createdUserId,
                "撤销卡读投影", 1, "{}");
        createdSessionId = session.id();
        long userTurn = frameService.openMessage(session.id(), "user", "撤销上一次写入");
        frameService.closeMessage(userTurn, "done");
        long answerTurn = frameService.openMessage(session.id(), "assistant", "写入已接受");
        frameService.closeMessage(answerTurn, "done");

        CaseInfo created = caseService.create(createdUserId, new CaseCreateInput(
                createdProjectId, "undo-visibility-card", "前置", "预期",
                1, 2, "web", null, 2, 1, null, List.of()));
        createdCaseIds.add(created.id());
        // The accepted write moved the live version forward: 1 -> 2. The undo
        // target is the pre-accept version 1, so the card must name it.
        jdbcTemplate.update("UPDATE tp_test_case SET version = 2 WHERE id = ?", created.id());
        jdbcTemplate.update("""
                        INSERT INTO tp_ai_chat_draft
                            (session_id, message_id, project_id, object_type, target_id,
                             base_version, schema_version, summary, payload_json,
                             status, seq, applied_at, applied_by)
                        VALUES (?, ?, ?, 'CASE_WRITE', ?, 1, '1',
                                'seeded applied case write', '{}'::jsonb,
                                2, 1, now(), ?)
                        """,
                session.id(), answerTurn, createdProjectId, created.id(), createdUserId);

        long cardId = undoService.undoLastAcceptedWrite(session.id());

        List<ChatMessageInfo> rows = frameService.listSealedMessages(createdUserId, session.id());
        List<ChatDraftInfo> folded = rows.stream()
                .filter(row -> row.id() == answerTurn)
                .findFirst().orElseThrow().drafts();
        ChatDraftInfo undo = folded.stream()
                .filter(draft -> draft.id() == cardId)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the undo card must fold back onto the newest sealed assistant "
                                + "row or the human can never confirm it; folded=" + folded));
        assertEquals("CASE_ROLLBACK", undo.objectType(),
                "undo rides the existing rollback kind — no new card type");
        assertEquals(1, undo.status(),
                "the undo card reads back pending: confirmation stays the only write door");
        assertTrue(undo.summary().contains("版本 1"),
                "the summary names the pre-accept target version for the human: "
                        + undo.summary());
        // The payload rides back as jsonb text — parse the anchor field
        // instead of matching a serialization shape.
        int targetVersion = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(undo.payloadJson()).path("targetVersion").asInt(-1);
        assertEquals(1, targetVersion,
                "the payload anchor survives the fold: " + undo.payloadJson());
    }
}
