package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionUsageItemView;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionUsagePageView;
import com.sixbean.testpike.core.aichat.domain.component.ChatFrameType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session-attribution ledger for the cost dashboard (E9 reconciliation, AO
 * batch phase 3): one row per conversation that spoke inside the asked
 * natural month, with the WINDOW token sum recomputed from the message rows —
 * the dashboard's reconciliation denominator never depends on the session
 * total alone, so a lost ledger movement shows up as a visible drift instead
 * of a silent wrong number. Project filter and paging mirror every other
 * core page door. Real dev database, zero mocks (same posture as {@link
 * ChatSessionUsageAccumulationTest}).
 */
@SpringBootTest(classes = CoreApplication.class)
class ChatUsageAttributionTest {

    @Autowired
    private ChatUsageAttributionService attributionService;

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

    /** One conversation with {@code turns} sealed answers of the given token pairs. */
    private ChatSessionInfo seedSessionWithUsage(long projectId, long userId, int... tokenPairs) {
        String tag = "t435u" + UUID.randomUUID().toString().replace("-", "").substring(0, 9);
        ChatSessionInfo session = sessionService.create(projectId, userId, "Attribution " + tag, 1, "{}");
        for (int i = 0; i < tokenPairs.length; i += 2) {
            long message = frameService.openMessage(session.id(), "assistant", null);
            // Stamp the turn into the current month so the default window catches it.
            jdbcTemplate.update("UPDATE tp_ai_chat_message SET created_at = ? WHERE id = ?",
                    java.sql.Timestamp.valueOf(LocalDateTime.now()), message);
            frameService.appendFrame(session.id(), message, ChatFrameType.DONE,
                    "{\"promptTokens\":" + tokenPairs[i] + ",\"completionTokens\":" + tokenPairs[i + 1] + "}",
                    null);
        }
        return session;
    }

    private String currentMonth() {
        return jdbcTemplate.queryForObject("SELECT to_char(now(), 'YYYY-MM')", String.class);
    }

    private ChatSessionUsageItemView findRow(ChatSessionUsagePageView page, long sessionId) {
        return page.list().stream().filter(r -> r.sessionId() == sessionId).findFirst().orElseThrow(
                () -> new AssertionError("session " + sessionId + " missing from attribution page"));
    }

    @Test
    void windowTokenSumsAreRecomputedFromTheMessageRows() {
        String tag = "proj" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long projectId = newProjectId(tag);
        long userId = newUserId("usr" + tag.substring(4));
        ChatSessionInfo session = seedSessionWithUsage(projectId, userId, 100, 20, 7, 3);

        ChatSessionUsagePageView page = attributionService.usageAttribution(
                currentMonth(), projectId, 1, 50);
        ChatSessionUsageItemView row = findRow(page, session.id());

        assertEquals(130L, row.windowTokens(),
                "the window sum is recomputed from message rows, not copied from the session total");
        assertEquals(2L, row.assistantTurns(), "only sealed answers carry usage");
        assertEquals(130L, row.sessionTotalTokens(),
                "the lifetime ledger agrees with the window when everything happened this month");
        assertEquals(tag, row.projectName(), "the row names its project slice");
        assertEquals("usr" + tag.substring(4), row.userName(), "the row names its owner");
    }

    @Test
    void turnsOutsideTheMonthStayOutOfTheWindowButTheLedgerStaysLifetime() {
        String tag = "proj" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long projectId = newProjectId(tag);
        long userId = newUserId("usr" + tag.substring(4));
        ChatSessionInfo session = seedSessionWithUsage(projectId, userId, 60, 10);

        // Age one turn out to last month: the window must drop it while the
        // lifetime session total keeps it (honest two columns, no quiet re-sum).
        jdbcTemplate.update("""
                UPDATE tp_ai_chat_message SET prompt_tokens = 40, completion_tokens = 5,
                       created_at = (now() - interval '45 days')
                WHERE session_id = ?
                """, session.id());

        ChatSessionUsagePageView page = attributionService.usageAttribution(
                currentMonth(), projectId, 1, 50);
        assertTrue(page.list().stream().noneMatch(r -> r.sessionId() == session.id()),
                "a session with no in-window turn does not claim a row this month");
    }

    @Test
    void malformedRequestsAreRefusedWithANamedReason() {
        assertThrows(ServiceException.class,
                () -> attributionService.usageAttribution("2026/10", null, 1, 20),
                "month must be the yyyy-MM natural-month key the cost doors already speak");
        assertThrows(ServiceException.class,
                () -> attributionService.usageAttribution(currentMonth(), null, 0, 20));
        assertThrows(ServiceException.class,
                () -> attributionService.usageAttribution(currentMonth(), null, 1, 0));
    }

    @Test
    void rowsOrderByWindowTokensDescendingAndThePageHonoursItsSize() {
        // Two conversations in one private segment: the heavier one must lead,
        // and a page of 1 must return exactly 1 row while the whole filtered
        // total travels alongside (footer never counts only the shown page).
        String tag = "proj" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long projectId = newProjectId(tag);
        long userId = newUserId("usr" + tag.substring(4));
        seedSessionWithUsage(projectId, userId, 500, 50);
        seedSessionWithUsage(projectId, userId, 10, 5);

        ChatSessionUsagePageView page1 = attributionService.usageAttribution(
                currentMonth(), projectId, 1, 1);
        assertEquals(1, page1.list().size(), "the page size is honoured");
        assertEquals(2L, page1.total(), "the whole filtered total travels with the window");
        assertEquals(550L, page1.list().get(0).windowTokens(),
                "the heaviest conversation leads the list");

        ChatSessionUsagePageView page2 = attributionService.usageAttribution(
                currentMonth(), projectId, 2, 1);
        assertEquals(15L, page2.list().get(0).windowTokens(), "page 2 carries the next row");
    }
}
