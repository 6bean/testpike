package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.api.dto.ChatDraftInfo;
import com.sixbean.testpike.core.aichat.api.dto.ChatMessageInfo;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.domain.component.ChatFrameType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Draft-card read projection (self-evolution loop, closed-card visibility):
 * a confirmable card that landed on the draft ledger must come back through
 * the sealed-history door, or a reloaded page shows an answer with no card
 * and the proposal becomes unconfirmable. This pins the fold on the real dev
 * database with zero mocks (same posture as {@code ChatMessageFeedbackTest}):
 * <ul>
 *   <li>a sealed assistant message with a landed draft carries exactly that
 *       draft on {@code listSealedMessages} — kind, payload, status;</li>
 *   <li>an applied proposal reads back with its version receipt so the card
 *       can re-render the applied state instead of pretending it is new;</li>
 *   <li>a rolled-back proposal keeps its once-only mark on read;</li>
 *   <li>messages without drafts carry an honest empty list (never null), and
 *       a draft never leaks onto its neighbour's message.</li>
 * </ul>
 * Fixtures create random-tagged users/projects so demo projects stay untouched.
 */
@SpringBootTest(classes = CoreApplication.class)
class ChatDraftReadProjectionTest {

    private static final String EVOLUTION_META =
            "{\"objectType\":\"EVOLUTION_PROPOSAL\",\"model\":\"projection-test-model\","
                    + "\"actionJson\":\"{\\\"subKind\\\":\\\"PROMPT_TPL\\\",\\\"domain\\\":\\\"dialog\\\"}\"}";

    @Autowired
    private ChatFrameService frameService;

    @Autowired
    private ChatSessionService sessionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ChatSessionInfo sessionWithLandedDraft() {
        return ChatProjectionTestSupport.sessionWithLandedDraft(
                frameService, sessionService, jdbcTemplate, EVOLUTION_META);
    }

    private List<ChatDraftInfo> draftsOf(List<ChatMessageInfo> rows, long messageId) {
        return rows.stream().filter(row -> row.id() == messageId)
                .findFirst().orElseThrow().drafts();
    }

    @Test
    void sealedAssistantMessageCarriesItsLandedDraftOnReload() {
        ChatSessionInfo session = sessionWithLandedDraft();
        long messageId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_ai_chat_message WHERE session_id = ? ORDER BY id",
                Long.class, session.id());

        List<ChatMessageInfo> rows = frameService.listSealedMessages(
                ChatProjectionTestSupport.ownerOf(jdbcTemplate, session.id()),
                session.id());

        List<ChatDraftInfo> drafts = draftsOf(rows, messageId);
        assertEquals(1, drafts.size(), "the landed draft must ride back on its own message");
        ChatDraftInfo draft = drafts.get(0);
        assertTrue(draft.id() > 0, "the card needs its ledger id to address the confirm door");
        assertEquals("EVOLUTION_PROPOSAL", draft.objectType());
        assertEquals(1, draft.status(), "a landed proposal reads back as pending confirmation");
        assertTrue(draft.payloadJson().contains("PROMPT_TPL"),
                "the proposal body is the payload the card renders from");
    }

    @Test
    void appliedDraftReadsBackWithItsVersionReceipt() {
        ChatSessionInfo session = sessionWithLandedDraft();
        long messageId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_ai_chat_message WHERE session_id = ? ORDER BY id",
                Long.class, session.id());
        jdbcTemplate.update("UPDATE tp_ai_chat_draft SET status = 2, applied_version = 7, "
                + "target_id = 501, base_version = 4 WHERE message_id = ?", messageId);

        List<ChatDraftInfo> drafts = draftsOf(
                frameService.listSealedMessages(ChatProjectionTestSupport.ownerOf(
                        jdbcTemplate, session.id()), session.id()), messageId);

        assertEquals(1, drafts.size());
        ChatDraftInfo applied = drafts.get(0);
        assertEquals(2, applied.status());
        assertEquals(Integer.valueOf(7), applied.appliedVersion(),
                "the receipt version must come from the ledger, never a client claim");
        assertEquals(Long.valueOf(501), applied.targetId());
        // The optimistic anchor must ride the fold: the confirm/rollback doors
        // re-validate against the base version server-side, and a reloaded
        // card that lost it could never pass that check honestly.
        assertEquals(Integer.valueOf(4), applied.baseVersion(),
                "the base-version anchor must come back from the ledger intact");
    }

    @Test
    void rolledBackProposalKeepsTheOnceOnlyMarkOnRead() {
        ChatSessionInfo session = sessionWithLandedDraft();
        long messageId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_ai_chat_message WHERE session_id = ? ORDER BY id",
                Long.class, session.id());
        jdbcTemplate.update("UPDATE tp_ai_chat_draft SET status = 2, applied_version = 3, "
                + "context_json = coalesce(context_json, '{}'::jsonb) "
                + "|| jsonb_build_object('rolledBackFrom', 3, 'rollbackVersion', 4) "
                + "WHERE message_id = ?", messageId);

        List<ChatDraftInfo> drafts = draftsOf(
                frameService.listSealedMessages(ChatProjectionTestSupport.ownerOf(
                        jdbcTemplate, session.id()), session.id()), messageId);

        assertEquals(1, drafts.size());
        assertTrue(drafts.get(0).rolledBack(),
                "a rolled-back card must not offer the rollback door a second time");
    }

    @Test
    void draftsFoldOnlyIntoTheirOwnMessageAndNeverLeakToNeighbours() {
        long userId = ChatProjectionTestSupport.insertUser(
                jdbcTemplate, ChatProjectionTestSupport.randomTag(jdbcTemplate, "drnu"));
        long projectId = ChatProjectionTestSupport.insertProject(
                jdbcTemplate, ChatProjectionTestSupport.randomTag(jdbcTemplate, "drnp"));
        ChatSessionInfo session = sessionService.create(projectId, userId, "草案归属", 1, "{}");
        long userTurn = frameService.openMessage(session.id(), "user", "帮我生成提案");
        frameService.closeMessage(userTurn, "done");
        long answerTurn = frameService.openMessage(session.id(), "assistant", null);
        frameService.appendFrame(session.id(), answerTurn, ChatFrameType.DRAFT, "提案卡", EVOLUTION_META);
        frameService.closeMessage(answerTurn, "done");

        List<ChatMessageInfo> rows = frameService.listSealedMessages(userId, session.id());
        assertEquals(2, rows.size());
        assertEquals(List.of(), draftsOf(rows, userTurn),
                "a message without a draft carries an honest empty list, never null");
        assertEquals(1, draftsOf(rows, answerTurn).size(),
                "the fold keys on message_id: only the card's own answer row carries it");
    }
}
