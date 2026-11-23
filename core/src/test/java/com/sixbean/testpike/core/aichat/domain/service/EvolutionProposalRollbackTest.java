package com.sixbean.testpike.core.aichat.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.api.dto.DraftApplyResult;
import com.sixbean.testpike.core.aichat.api.dto.DraftTicket;
import com.sixbean.testpike.core.aichat.api.dto.EvolutionProposalView;
import com.sixbean.testpike.core.prompt.api.dto.PromptTemplateItem;
import com.sixbean.testpike.core.prompt.api.dto.PromptTemplateSaveInput;
import com.sixbean.testpike.core.prompt.domain.service.PromptTemplateService;
import com.sixbean.testpike.core.support.EvolutionFixtureLifecycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One-click rollback of a landed self-evolution proposal (AF-i phase 3).
 * <p>The rollback never invents a version chain: it consumes the prompt
 * domain's existing precedent — the previous family version is copied forward
 * as a new row with {@code rollback_to} naming its source (same semantics the
 * human template page uses). What this pins on the real dev database:
 * <ul>
 *   <li>confirming a prompt card then rolling it back lands one new family
 *       version whose content equals the pre-proposal version and whose
 *       rollback_to names that pre-proposal row;</li>
 *   <li>the rollback is audited with the evolution attribution: operator,
 *       the triggering run (draft session), the undone card and the produced
 *       version all traceable from one audit row;</li>
 *   <li>double rollback of the same card refuses loudly and writes nothing
 *       (the draft row carries the once-only mark);</li>
 *   <li>a card that was never applied, and another user's card, are refused
 *       (pending-state guard + session ownership, same doors as apply);</li>
 *   <li>non-prompt families (tool/grounding) honestly refuse — they have no
 *       version chain to roll back.</li>
 * </ul>
 * Fixtures use random-tagged users/projects and a dedicated template family
 * seeded before the run; every created row is cleaned afterwards.
 */
@SpringBootTest(classes = CoreApplication.class)
class EvolutionProposalRollbackTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private EvolutionProposalService proposalService;

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

    private String currentMonth() {
        return YearMonth.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }

    /** A sealed assistant answer carrying a 👎 complaint inside the window. */
    private void sealedComplaint(long projectId, long userId, String title, String reason) {
        ChatSessionInfo session = sessionService.create(projectId, userId, title, 2, "{}");
        sessionIds.add(session.id());
        long messageId = frameService.openMessage(session.id(), "assistant", "答案正文");
        frameService.closeMessage(messageId, "done");
        frameService.recordFeedback(messageId, userId, -1, reason);
    }

    /** Run + confirm the prompt card, returning its apply receipt. */
    private DraftApplyResult confirmedPromptCard(long projectId, long userId) {
        EvolutionProposalView run = proposalService.generate(currentMonth(), projectId, userId);
        EvolutionProposalView.ProposalRow card = run.proposals().stream()
                .filter(p -> "PROMPT_TPL".equals(p.subKind()))
                .findFirst().orElseThrow();
        DraftTicket ticket = confirmationService.issueConfirmToken(userId, card.draftId());
        return confirmationService.apply(userId, card.draftId(), ticket.token());
    }

    @BeforeEach
    void resetTrackedFixtureHandles() {
        EvolutionFixtureLifecycle.begin(sessionIds, projectIds, userIds);
    }

    @AfterEach
    void sweepTrackedEvolutionRows() {
        EvolutionFixtureLifecycle.sweep(jdbcTemplate, sessionIds, projectIds, userIds);
    }

    @Test
    void confirmedPromptCardRollsBackThroughTheVersionChainPrecedent() throws Exception {
        long userId = newUserId(tag("ebk"));
        long projectId = newProjectId(tag("ebk"));
        // Seed the family the generator targets so the proposal lands as
        // version 2 and a real previous version exists to roll back to.
        promptTemplateService.save(new PromptTemplateSaveInput(
                "dialog-answer-prompt", "对话", "原始模板正文", userId));

        sealedComplaint(projectId, userId, "点踩样本", "答非所问：没答部署流程");
        EvolutionProposalView run = proposalService.generate(currentMonth(), projectId, userId);
        EvolutionProposalView.ProposalRow card = run.proposals().stream()
                .filter(p -> "PROMPT_TPL".equals(p.subKind())).findFirst().orElseThrow();
        DraftTicket ticket = confirmationService.issueConfirmToken(userId, card.draftId());
        DraftApplyResult applied = confirmationService.apply(userId, card.draftId(), ticket.token());
        assertNotNull(applied.appliedVersion(), "prompt family records the applied version");

        PromptTemplateItem rolled = proposalService.rollback(card.draftId(), userId);
        assertNotNull(rolled);
        assertEquals(applied.appliedVersion() + 1, rolled.version(),
                "rollback copies forward as the next family version");
        PromptTemplateItem appliedRow = promptTemplateService.detail(applied.targetId());
        assertNotNull(appliedRow);
        assertEquals(rolled.version(),
                promptTemplateService.listVersions(appliedRow.name(), appliedRow.domain()).get(0).version(),
                "the rolled-back version becomes the newest family row");
        assertNotNull(rolled.rollbackTo(), "rollback_to names the copied source row");
        PromptTemplateItem source = promptTemplateService.detail(rolled.rollbackTo());
        assertEquals(appliedRow.version() - 1, source.version(),
                "the rollback source is the version that preceded the proposal");
        assertEquals(source.content(), rolled.content(),
                "rollback copies the source content forward");

        // Evolution attribution is in the audit ledger: operator, the run
        // (draft session), the undone card and the produced version.
        JsonNode after = objectMapper.readTree(jdbcTemplate.queryForObject(
                "SELECT after_json::text FROM tp_audit_log WHERE action = ?"
                        + " AND object_id = ? ORDER BY id DESC LIMIT 1",
                String.class, "evolution-proposal-rollback", String.valueOf(card.draftId())));
        assertEquals(run.sessionId(), after.path("evolutionSessionId").asLong(),
                "the triggering run is traceable from the audit row");
        assertTrue(after.path("newVersion").asInt() > 0);
        assertTrue(after.path("undoneVersion").asInt() > 0);
    }

    @Test
    void secondRollbackOfTheSameCardIsRefusedAndWritesNothing() throws Exception {
        long userId = newUserId(tag("ebk"));
        long projectId = newProjectId(tag("ebk"));
        // Seed a real predecessor so the first rollback has a chain to copy.
        promptTemplateService.save(new PromptTemplateSaveInput(
                "dialog-answer-prompt", "对话", "原始模板正文", userId));
        sealedComplaint(projectId, userId, "点踩样本", "回答太泛");
        EvolutionProposalView run = proposalService.generate(currentMonth(), projectId, userId);
        long cardId = run.proposals().stream()
                .filter(p -> "PROMPT_TPL".equals(p.subKind())).findFirst().orElseThrow().draftId();
        DraftTicket ticket = confirmationService.issueConfirmToken(userId, cardId);
        confirmationService.apply(userId, cardId, ticket.token());

        proposalService.rollback(cardId, userId);
        int versionsAfterFirstRollback = countFamilyRowsOfSession(cardId);
        ServiceException refused = assertThrows(ServiceException.class,
                () -> proposalService.rollback(cardId, userId));
        assertTrue(refused.getMessage().contains("已回滚"), "plain refusal names the state");
        assertEquals(versionsAfterFirstRollback, countFamilyRowsOfSession(cardId),
                "the refused retry created no template row");
    }

    @Test
    void pendingCardRollbackIsRefusedBeforeAnyWrite() {
        long userId = newUserId(tag("ebk"));
        long projectId = newProjectId(tag("ebk"));
        sealedComplaint(projectId, userId, "点踩样本", "回答太泛");
        EvolutionProposalView run = proposalService.generate(currentMonth(), projectId, userId);
        long cardId = run.proposals().stream()
                .filter(p -> "PROMPT_TPL".equals(p.subKind())).findFirst().orElseThrow().draftId();

        ServiceException refused = assertThrows(ServiceException.class,
                () -> proposalService.rollback(cardId, userId));
        assertTrue(refused.getMessage().contains("未生效") || refused.getMessage().contains("确认"),
                "the refusal says the card was never applied");
        assertEquals(0, countAuditRows("evolution-proposal-rollback", String.valueOf(cardId)),
                "a refused rollback leaves no audit trail of a write");
    }

    @Test
    void foreignUserRollbackIsRefusedBySessionOwnership() {
        long userId = newUserId(tag("ebk"));
        long otherId = newUserId(tag("other"));
        long projectId = newProjectId(tag("ebk"));
        sealedComplaint(projectId, userId, "点踩样本", "回答太泛");
        EvolutionProposalView run = proposalService.generate(currentMonth(), projectId, userId);
        long cardId = run.proposals().stream()
                .filter(p -> "PROMPT_TPL".equals(p.subKind())).findFirst().orElseThrow().draftId();
        DraftTicket ticket = confirmationService.issueConfirmToken(userId, cardId);
        confirmationService.apply(userId, cardId, ticket.token());

        ServiceException refused = assertThrows(ServiceException.class,
                () -> proposalService.rollback(cardId, otherId));
        assertTrue(refused.getMessage().contains("不属于当前用户"),
                "horizontal access refusal stays the same sentence family as apply");
    }

    @Test
    void toolFamilyCardRollbackRefusesHonestlyWithoutVersionChain() {
        long userId = newUserId(tag("ebk"));
        long projectId = newProjectId(tag("ebk"));
        String failingTool = tag("取数失败");
        sealedComplaint(projectId, userId, "点踩样本", "回答太泛");
        seedToolFailures(failingTool, 2);
        EvolutionProposalView run = proposalService.generate(currentMonth(), projectId, userId);
        long toolCard = run.proposals().stream()
                .filter(p -> "TOOL_DEF".equals(p.subKind())).findFirst().orElseThrow().draftId();
        DraftTicket ticket = confirmationService.issueConfirmToken(userId, toolCard);
        confirmationService.apply(userId, toolCard, ticket.token());

        ServiceException refused = assertThrows(ServiceException.class,
                () -> proposalService.rollback(toolCard, userId));
        assertTrue(refused.getMessage().contains("不支持"),
                "the refusal says this family has no version chain to roll back");
        try {
            jdbcTemplate.update("DELETE FROM tp_tool WHERE name = ?", failingTool);
        } catch (RuntimeException ignored) {
            // fixture cleanup is best-effort; the refusal above is the assertion
        }
    }

    /** Failing invocations of a tool name that has no registered tp_tool row. */
    private void seedToolFailures(String toolName, int times) {
        for (int i = 0; i < times; i++) {
            jdbcTemplate.update("""
                    INSERT INTO tp_tool_invocation (caller, tool_name, success, error)
                    VALUES ('ai', ?, 0, '夹具失败：上游超时')
                    """, toolName);
        }
    }

    private int countFamilyRowsOfSession(long draftId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM tp_prompt_template t
                WHERE t.name = (SELECT d.payload_json::json ->> 'name'
                                FROM tp_ai_chat_draft d WHERE d.id = ?)
                """, Integer.class, draftId);
        return count == null ? 0 : count;
    }

    private int countAuditRows(String action, String objectId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE action = ? AND object_id = ?",
                Integer.class, action, objectId);
        return count == null ? 0 : count;
    }
}
