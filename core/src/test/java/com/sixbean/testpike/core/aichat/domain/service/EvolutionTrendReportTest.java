package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.api.dto.DraftTicket;
import com.sixbean.testpike.core.aichat.api.dto.EvolutionProposalView;
import com.sixbean.testpike.core.aichat.api.dto.EvolutionTrendView;
import com.sixbean.testpike.core.prompt.api.dto.PromptTemplateSaveInput;
import com.sixbean.testpike.core.prompt.domain.service.PromptTemplateService;
import com.sixbean.testpike.core.support.EvolutionFixtureLifecycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Before/after trend report of applied self-evolution proposals (AF-i phase 4).
 * <p>The report never grows a second observation spec: both sides of the
 * comparison are the same rollup the proposal run already reads
 * ({@link EvolutionSignalService#observe}), shifted one natural month. What
 * this pins on the real dev database:
 * <ul>
 *   <li>a run whose comparison month has not completed yet reports "watching"
 *       honestly and marks nothing;</li>
 *   <li>an applied card whose next month shows the same complaint shape is
 *       auto-marked ineffective — the mark lands on the draft row (no silent
 *       pile-up) and names the window it compared;</li>
 *   <li>a next month with a sealed answer and no follow-up/complaint is
 *       judged improved and carries no mark;</li>
 *   <li>a next month without any sealed answer stays "insufficient" instead
 *       of pretending a verdict;</li>
 *   <li>the rollup recomputes live — changing the after window between two
 *       calls moves the verdict and replaces the stale mark;</li>
 *   <li>a malformed month key refuses fail-closed before any read/write.</li>
 * </ul>
 * Fixtures backdate message rows with a plain UPDATE (the rollup windows on
 * created_at), use random-tagged users/projects and clean every row up.
 */
@SpringBootTest(classes = CoreApplication.class)
class EvolutionTrendReportTest {

    private static final DateTimeFormatter MONTH_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM");

    @Autowired
    private EvolutionProposalService proposalService;

    @Autowired
    private EvolutionTrendService trendService;

    @Autowired
    private DraftConfirmationService confirmationService;

    @Autowired
    private PromptTemplateService promptTemplateService;

    @Autowired
    private ChatSessionService sessionService;

    @Autowired
    private ChatFrameService frameService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> sessionIds = new ArrayList<>();
    private final List<Long> projectIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    private String tag(String stem) {
        return stem + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private long newUserId(String name) {
        long id = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, '$2a$10$x', ?, 1, 1) RETURNING id
                """, Long.class, name, name);
        userIds.add(id);
        return id;
    }

    private long newProjectId(String name) {
        long id = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, name, name);
        projectIds.add(id);
        return id;
    }

    /**
     * One sealed assistant answer with a 👎 complaint plus a follow-up user
     * message, both backdated inside the given month window.
     */
    private void complaintTurn(long projectId, long userId, String title, YearMonth month) {
        ChatSessionInfo session = sessionService.create(projectId, userId, title, 2, "{}");
        sessionIds.add(session.id());
        long messageId = frameService.openMessage(session.id(), "assistant", "答案正文");
        frameService.closeMessage(messageId, "done");
        frameService.recordFeedback(messageId, userId, -1, "答非所问：没答部署流程");
        long followUpId = frameService.openMessage(session.id(), "user", "那到底怎么部署");
        frameService.closeMessage(followUpId, "done");
        backdate(messageId, month.atDay(10));
        backdate(followUpId, month.atDay(10));
    }

    /** One sealed assistant answer WITHOUT follow-up inside the month window. */
    private void cleanAnswer(long projectId, long userId, String title, YearMonth month) {
        ChatSessionInfo session = sessionService.create(projectId, userId, title, 2, "{}");
        sessionIds.add(session.id());
        long messageId = frameService.openMessage(session.id(), "assistant", "答案正文");
        frameService.closeMessage(messageId, "done");
        backdate(messageId, month.atDay(8));
    }

    private void backdate(long messageId, LocalDate day) {
        jdbcTemplate.update("UPDATE tp_ai_chat_message SET created_at = ? WHERE id = ?",
                day.atStartOfDay(), messageId);
    }

    /** Generate a run in the given month and confirm its prompt card. */
    private long appliedPromptDraftId(long projectId, long userId, YearMonth month) {
        String monthKey = month.format(MONTH_FORMAT);
        promptTemplateService.save(new PromptTemplateSaveInput(
                "dialog-answer-prompt", "对话", "原始模板正文", userId));
        complaintTurn(projectId, userId, "点踩样本 " + monthKey, month);
        EvolutionProposalView run = proposalService.generate(monthKey, projectId, userId);
        sessionIds.add(run.sessionId());
        EvolutionProposalView.ProposalRow card = run.proposals().stream()
                .filter(p -> "PROMPT_TPL".equals(p.subKind()))
                .findFirst().orElseThrow();
        DraftTicket ticket = confirmationService.issueConfirmToken(userId, card.draftId());
        confirmationService.apply(userId, card.draftId(), ticket.token());
        return card.draftId();
    }

    private String contextText(long draftId, String key) {
        return jdbcTemplate.queryForObject(
                "SELECT context_json ->> ? FROM tp_ai_chat_draft WHERE id = ?",
                String.class, key, draftId);
    }

    @BeforeEach
    void clearFixtureHandles() {
        EvolutionFixtureLifecycle.begin(sessionIds, projectIds, userIds);
    }

    @AfterEach
    void removeFixtureCreatedRows() {
        EvolutionFixtureLifecycle.sweep(jdbcTemplate, sessionIds, projectIds, userIds);
    }

    @Test
    void currentMonthRunStaysWatchingAndMarksNothing() {
        long userId = newUserId(tag("trew"));
        long projectId = newProjectId(tag("trew"));
        YearMonth current = YearMonth.now();
        long draftId = appliedPromptDraftId(projectId, userId, current);

        EvolutionTrendView report = trendService.trend(current.format(MONTH_FORMAT), projectId);

        assertEquals(1, report.cards().size(), "only the applied prompt card of this run");
        assertEquals("watching", report.cards().get(0).verdict(),
                "a comparison month that has not completed is honestly watching");
        assertTrue(!report.afterMonthComplete());
        assertNull(contextText(draftId, "trendVerdict"), "watching writes no mark");
    }

    @Test
    void unchangedNextMonthAutoMarksIneffective() {
        long userId = newUserId(tag("trei"));
        long projectId = newProjectId(tag("trei"));
        YearMonth runMonth = YearMonth.now().minusMonths(3);
        YearMonth afterMonth = runMonth.plusMonths(1);
        long draftId = appliedPromptDraftId(projectId, userId, runMonth);
        // The next month shows the same complaint shape: nothing improved.
        complaintTurn(projectId, userId, "改后仍点踩", afterMonth);

        EvolutionTrendView report = trendService.trend(runMonth.format(MONTH_FORMAT), projectId);

        assertTrue(report.afterMonthComplete(), "the comparison month has fully elapsed");
        assertEquals(afterMonth.format(MONTH_FORMAT), report.afterMonth());
        assertEquals("ineffective", report.cards().get(0).verdict());
        assertEquals("ineffective", contextText(draftId, "trendVerdict"),
                "the verdict lands on the draft row — no silent pile-up");
        assertEquals(afterMonth.format(MONTH_FORMAT), contextText(draftId, "trendComparedMonth"),
                "the mark names which window it compared");
    }

    @Test
    void cleanNextMonthIsJudgedImprovedWithoutMark() {
        long userId = newUserId(tag("tre4"));
        long projectId = newProjectId(tag("tre4"));
        YearMonth runMonth = YearMonth.now().minusMonths(2);
        YearMonth afterMonth = runMonth.plusMonths(1);
        long draftId = appliedPromptDraftId(projectId, userId, runMonth);
        // One sealed answer, no follow-up, no complaint in the next month.
        cleanAnswer(projectId, userId, "改后样本", afterMonth);

        EvolutionTrendView report = trendService.trend(runMonth.format(MONTH_FORMAT), projectId);

        assertEquals("improved", report.cards().get(0).verdict());
        assertNull(contextText(draftId, "trendVerdict"),
                "an improved card carries no ineffective mark");
    }

    @Test
    void emptyNextMonthStaysInsufficientInsteadOfFakeVerdict() {
        long userId = newUserId(tag("tre5"));
        long projectId = newProjectId(tag("tre5"));
        YearMonth runMonth = YearMonth.now().minusMonths(4);
        long draftId = appliedPromptDraftId(projectId, userId, runMonth);

        EvolutionTrendView report = trendService.trend(runMonth.format(MONTH_FORMAT), projectId);

        assertEquals("insufficient", report.cards().get(0).verdict(),
                "no sealed answer in the comparison window proves neither way");
        assertNull(contextText(draftId, "trendVerdict"));
    }

    @Test
    void verdictRecomputesFromLiveLedgersAndReplacesStaleMark() {
        long userId = newUserId(tag("trec"));
        long projectId = newProjectId(tag("trec"));
        YearMonth runMonth = YearMonth.now().minusMonths(5);
        YearMonth afterMonth = runMonth.plusMonths(1);
        long draftId = appliedPromptDraftId(projectId, userId, runMonth);
        complaintTurn(projectId, userId, "改后仍点踩", afterMonth);
        assertEquals("ineffective",
                trendService.trend(runMonth.format(MONTH_FORMAT), projectId)
                        .cards().get(0).verdict());

        // The after window turns clean (sealed answer, no complaint): the
        // next call re-reads the live ledger and the stale mark is replaced.
        cleanAnswer(projectId, userId, "改后转好", afterMonth);
        assertEquals("improved",
                trendService.trend(runMonth.format(MONTH_FORMAT), projectId)
                        .cards().get(0).verdict());
        assertEquals("improved", contextText(draftId, "trendVerdict"),
                "the draft row carries the newest verdict, not the stale one");
    }

    @Test
    void malformedMonthRefusesFailClosed() {
        long userId = newUserId(tag("tre6"));
        long projectId = newProjectId(tag("tre6"));
        ServiceException impossible = assertThrows(ServiceException.class,
                () -> trendService.trend("2026-13", projectId));
        assertTrue(impossible.getMessage().contains("yyyy-MM"));
        ServiceException unshaped = assertThrows(ServiceException.class,
                () -> trendService.trend("October", projectId));
        assertTrue(unshaped.getMessage().contains("yyyy-MM"));
    }
}
