package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.api.dto.ChatMessageInfo;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Goal-plan reload projection : the collapsible progress
 * card a live turn folds from the open-tail state frame must survive a page
 * reload. Sealed history rows do not replay frames through this door, so the
 * current ledger of the session joins onto the newest sealed assistant row
 * server-side — the same posture as the draft-card fold . This pins the
 * fold on the real dev database with zero mocks:
 * <ul>
 * <li>a session whose ledger still owes steps projects the open tail onto
 * the newest sealed assistant row — count, titles and the exact resume
 * sentence wording the live frame uses;</li>
 * <li>an empty ledger, a fully-closed ledger or an absent ledger project
 * nothing (the reload view shows no card instead of a fake one);</li>
 * <li>a corrupt step list fails soft to "no ledger", never a thrown read;</li>
 * <li>the fold rides only the newest assistant row — neighbours and user
 * echoes carry an honest null.</li>
 * </ul>
 * Fixtures create random-tagged users/projects so demo projects stay untouched.
 */
@SpringBootTest(classes = CoreApplication.class)
class ChatGoalPlanReadProjectionTest {

    @Autowired
    private ChatFrameService frameService;

    @Autowired
    private ChatSessionService sessionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private void insertLedger(long sessionId, String stepsJson) {
        jdbcTemplate.update("INSERT INTO tp_ai_chat_goal_plan"
                        + " (session_id, plan_version, steps_json, revision) VALUES (?, 1, ?, 1)",
                sessionId, stepsJson);
    }

    private ChatMessageInfo assistantRow(List<ChatMessageInfo> rows, long messageId) {
        return rows.stream().filter(row -> row.id() == messageId)
                .findFirst().orElseThrow();
    }

    private long newestAnswerId(long sessionId) {
        return jdbcTemplate.queryForObject("SELECT max(id) FROM tp_ai_chat_message"
                + " WHERE session_id = ? AND role = 'assistant'", Long.class, sessionId);
    }

    private List<ChatMessageInfo> sealedRows(ChatSessionInfo session, long userId) {
        return frameService.listSealedMessages(userId, session.id());
    }

    @Test
    void openLedgerProjectsItsTailOntoTheNewestAssistantRow() {
        ChatSessionInfo session =
                ChatProjectionTestSupport.sessionWithSealedAnswer(frameService, sessionService, jdbcTemplate);
        long userId =                 ChatProjectionTestSupport.ownerOf(jdbcTemplate, session.id());
        insertLedger(session.id(), "[{\"title\":\"整理需求\",\"state\":\"done\",\"evidence\":\"tool:1\"},"
                + "{\"title\":\"生成用例\",\"state\":\"pending\",\"evidence\":\"\"},"
                + "{\"title\":\"评审确认\",\"state\":\"blocked\",\"evidence\":\"\"}]");

        ChatMessageInfo answer = assistantRow(sealedRows(session, userId), newestAnswerId(session.id()));

        assertNotNull(answer.goalPlan(), "an open ledger must ride the reload view");
        assertEquals(2, answer.goalPlan().openCount(),
                "pending and blocked are the still-open steps (skipped/done close a step)");
        assertEquals(List.of("生成用例", "评审确认"), answer.goalPlan().openTitles(),
                "the enumerated open tail keeps stored order");
        // Per-step states ride the projection : the
        // reload card must be able to render a blocked step as blocked
        // instead of flattening every open row to pending.
        assertEquals(List.of("pending", "blocked"), answer.goalPlan().openStates(),
                "each open title keeps its own stored state, parallel to the titles");
        // The sentence is the live frame's wording verbatim — the front-end
        // card door recognises it by the pinned prefix, so the reload fold
        // must never re-invent a second phrasing (one folding semantics).
        assertEquals("目标计划还有 2 步未完成（生成用例、评审确认）。回复「继续」即可接着执行。",
                answer.goalPlan().openTailSentence());
        assertEquals(1, answer.goalPlan().planVersion());
    }

    @Test
    void closedOrAbsentLedgerProjectsNothing() {
        ChatSessionInfo closed =
                ChatProjectionTestSupport.sessionWithSealedAnswer(frameService, sessionService, jdbcTemplate);
        long closedUser =                 ChatProjectionTestSupport.ownerOf(jdbcTemplate, closed.id());
        insertLedger(closed.id(), "[{\"title\":\"整理需求\",\"state\":\"done\",\"evidence\":\"cite:1\"}]");
        assertNull(assistantRow(sealedRows(closed, closedUser), newestAnswerId(closed.id())).goalPlan(),
                "a ledger with nothing open owes no card");

        ChatSessionInfo absent =
                ChatProjectionTestSupport.sessionWithSealedAnswer(frameService, sessionService, jdbcTemplate);
        long absentUser =                 ChatProjectionTestSupport.ownerOf(jdbcTemplate, absent.id());
        assertNull(assistantRow(sealedRows(absent, absentUser), newestAnswerId(absent.id())).goalPlan(),
                "no ledger row means no card, never a fabricated one");
    }

    @Test
    void corruptLedgerFailsSoftToNoCard() {
        ChatSessionInfo session =
                ChatProjectionTestSupport.sessionWithSealedAnswer(frameService, sessionService, jdbcTemplate);
        long userId =                 ChatProjectionTestSupport.ownerOf(jdbcTemplate, session.id());
        insertLedger(session.id(), "not-a-json-step-list");
        assertNull(assistantRow(sealedRows(session, userId), newestAnswerId(session.id())).goalPlan(),
                "a corrupt stored list degrades to \"no ledger\", never a thrown read");
    }

    @Test
    void tailRidesOnlyTheNewestAssistantRow() {
        ChatSessionInfo session =
                ChatProjectionTestSupport.sessionWithSealedAnswer(frameService, sessionService, jdbcTemplate);
        long userId =                 ChatProjectionTestSupport.ownerOf(jdbcTemplate, session.id());
        insertLedger(session.id(), "[{\"title\":\"生成用例\",\"state\":\"pending\",\"evidence\":\"\"}]");

        List<ChatMessageInfo> rows = sealedRows(session, userId);
        assertEquals(2, rows.size());
        long userTurn = jdbcTemplate.queryForObject("SELECT min(id) FROM tp_ai_chat_message"
                + " WHERE session_id = ? AND role = 'user'", Long.class, session.id());
        assertNull(assistantRow(rows, userTurn).goalPlan(),
                "user echoes carry an honest null fold");
        assertNotNull(assistantRow(rows, newestAnswerId(session.id())).goalPlan());
    }
}
