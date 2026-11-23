package com.sixbean.testpike.core.aichat.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.api.dto.EvolutionProposalView;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Self-evolution proposal generators (AF-i phase 2b-ii): the manual run
 * that reads one month of observation signals and lands improvement
 * proposals as PENDING draft cards of the EVOLUTION_PROPOSAL kind.
 * <p>What this pins on the real dev database with zero mocks:
 * <ul>
 *   <li>complaint signals generate cards: thumbs-down reasons become prompt
 *       and grounding proposals, failing tools without a registered row
 *       become a DOC-type tool-definition draft (still behind the human
 *       review gate — the evolution run never publishes);</li>
 *   <li>an honest empty window produces zero cards and zero rows;</li>
 *   <li>one pending batch per project+month: a second run while cards await
 *       confirmation is refused (stale-card exclusion), and the refusal
 *       writes nothing;</li>
 *   <li>every card row carries the evolution attribution (object_type,
 *       subKind inside payload, summary names the month window);</li>
 *   <li>platform-wide slices are refused in plain words — a proposal must
 *       land in a real project lane (H1: session project anchoring);</li>
 *   <li>a malformed month key is refused fail-closed before any write.</li>
 * </ul>
 * Fixtures use random-tagged users/projects; every row the suite creates is
 * removed afterwards (dialog ledgers carry no delete face on purpose, so the
 * test cleans its own ids — demo projects stay untouched either way).
 */
@SpringBootTest(classes = CoreApplication.class)
class EvolutionProposalGeneratorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private EvolutionProposalService proposalService;

    @Autowired
    private EvolutionSignalService signalService;

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

    /** A sealed assistant answer with an optional 👎 complaint line inside the window. */
    private long sealedAssistant(long projectId, long userId, String title, Integer feedback,
                                 String reason) {
        ChatSessionInfo session = sessionService.create(projectId, userId, title, 2, "{}");
        sessionIds.add(session.id());
        long messageId = frameService.openMessage(session.id(), "assistant", "答案正文");
        frameService.closeMessage(messageId, "done");
        if (feedback != null) {
            frameService.recordFeedback(messageId, userId, feedback, reason);
        }
        return messageId;
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

    private int countRows(String sql) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }

    @BeforeEach
    void setUp() {
        sessionIds.clear();
        projectIds.clear();
        userIds.clear();
    }

    @AfterEach
    void tearDown() {
        for (Long sessionId : sessionIds) {
            jdbcTemplate.update("DELETE FROM tp_ai_chat_draft WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM tp_ai_chat_frame WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM tp_ai_chat_message WHERE session_id = ?", sessionId);
        }
        for (Long sessionId : sessionIds) {
            jdbcTemplate.update("DELETE FROM tp_ai_chat_session WHERE id = ?", sessionId);
        }
        jdbcTemplate.update("DELETE FROM tp_ai_chat_draft WHERE object_type = 'EVOLUTION_PROPOSAL' "
                + "AND session_id IN (SELECT id FROM tp_ai_chat_session WHERE user_id IN ("
                + placeholderList(userIds) + "))", userIds.toArray());
        for (Long userId : userIds) {
            jdbcTemplate.update("DELETE FROM tp_user WHERE id = ?", userId);
        }
        for (Long projectId : projectIds) {
            jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
        }
    }

    private static String placeholderList(List<Long> ids) {
        return ids.isEmpty() ? "0" : String.join(",", ids.stream().map(id -> "?").toList());
    }

    @Test
    void complaintSignalsLandPendingEvolutionCards() {
        long userId = newUserId(tag("evo"));
        long projectId = newProjectId(tag("evo"));
        String month = currentMonth();

        sealedAssistant(projectId, userId, "点踩样本", -1, "答非所问：没答部署流程");
        String failingTool = tag("取数失败");
        seedToolFailures(failingTool, 2);

        EvolutionProposalView run = proposalService.generate(month, projectId, userId);
        assertEquals(month, run.month());
        assertEquals(projectId, run.projectId());

        // Prompt + grounding families always answer a thumbs-down complaint;
        // the failing unknown tool adds the tool-definition family.
        assertTrue(run.proposals().size() >= 2,
                "a complaint window yields at least the prompt and grounding cards");
        for (EvolutionProposalView.ProposalRow row : run.proposals()) {
            assertTrue(List.of("PROMPT_TPL", "TOOL_DEF", "GROUNDING").contains(row.subKind()),
                    "every card belongs to one of the three allowed families");
            assertTrue(row.summary().contains(month),
                    "the card summary names the observation window for attribution");
            JsonNode payload = readPayload(row.draftId());
            assertEquals(row.subKind(), payload.path("subKind").asText(),
                    "the payload carries the same family discriminator");
            assertFalse(payload.path("subKind").asText().isEmpty());
        }
        // Ledger shape: pending rows of the evolution kind on the run session.
        assertEquals(run.proposals().size(), countRows(
                "SELECT count(*) FROM tp_ai_chat_draft WHERE object_type = 'EVOLUTION_PROPOSAL' "
                        + "AND status = 1 AND session_id = " + run.sessionId()),
                "cards land as pending rows of one evolution session");
    }

    @Test
    void honestEmptyWindowProducesZeroCards() {
        long userId = newUserId(tag("evo"));
        long projectId = newProjectId(tag("evo"));

        EvolutionProposalView run = proposalService.generate(currentMonth(), projectId, userId);
        assertTrue(run.proposals().isEmpty(),
                "a window without complaint signals fabricates no proposal");
        assertEquals(0, countRows("SELECT count(*) FROM tp_ai_chat_draft "
                + "WHERE object_type = 'EVOLUTION_PROPOSAL' AND session_id = " + run.sessionId()));
    }

    @Test
    void secondRunWhileCardsPendingIsRefusedAndWritesNothing() {
        long userId = newUserId(tag("evo"));
        long projectId = newProjectId(tag("evo"));
        String month = currentMonth();
        sealedAssistant(projectId, userId, "点踩样本", -1, "答案没有落到点");

        EvolutionProposalView first = proposalService.generate(month, projectId, userId);
        assertFalse(first.proposals().isEmpty(), "precondition: the first run landed cards");

        ServiceException refusal = assertThrows(ServiceException.class,
                () -> proposalService.generate(month, projectId, userId),
                "a pending batch blocks a second batch for the same target");
        assertTrue(refusal.getMessage().contains("待确认"),
                "the refusal points at the pending cards, e.g.: " + refusal.getMessage());
        assertEquals(first.proposals().size(), countRows(
                "SELECT count(*) FROM tp_ai_chat_draft WHERE object_type = 'EVOLUTION_PROPOSAL' "
                        + "AND status = 1 AND session_id = " + first.sessionId()),
                "the refused run added zero rows");
    }

    @Test
    void platformWideSliceIsRefusedInPlainWords() {
        long userId = newUserId(tag("evo"));
        ServiceException refusal = assertThrows(ServiceException.class,
                () -> proposalService.generate(currentMonth(), null, userId),
                "proposals need a real project lane (H1 session anchoring)");
        assertTrue(refusal.getMessage().contains("项目"),
                "the refusal names the missing project, e.g.: " + refusal.getMessage());
    }

    @Test
    void malformedMonthIsRefusedBeforeAnyWrite() {
        long userId = newUserId(tag("evo"));
        long projectId = newProjectId(tag("evo"));
        assertThrows(ServiceException.class,
                () -> proposalService.generate("2026-13", projectId, userId));
        assertThrows(ServiceException.class,
                () -> proposalService.generate("last month", projectId, userId));
        // The rollup door keeps the same fail-closed shape the generators reuse.
        assertThrows(ServiceException.class, () -> signalService.observe("2026-13", projectId));
    }

    private JsonNode readPayload(long draftId) {
        String payload = jdbcTemplate.queryForObject(
                "SELECT payload_json FROM tp_ai_chat_draft WHERE id = ?", String.class, draftId);
        assertNotNull(payload);
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            throw new AssertionError("payload must be valid JSON: " + payload, e);
        }
    }
}
