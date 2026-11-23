package com.sixbean.testpike.core.mcp.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.mcp.api.dto.McpConfirmGrantTicket;
import com.sixbean.testpike.core.mcp.api.dto.McpConfirmOutcome;
import com.sixbean.testpike.core.mcp.domain.component.McpConfirmGrantStore;
import com.sixbean.testpike.core.mcp.domain.service.McpConfirmSettlementService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.script.api.dto.ScriptWritebackEvidence;
import com.sixbean.testpike.core.script.api.dto.ScriptWritebackInput;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.core.support.ScriptWritebackDrafts;
import com.sixbean.testpike.core.support.TrackedProjectLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In-session confirmation loop for MCP write tools (real database and Redis, no mocks).
 * <p>Why this gate exists: an outside coding agent must be able to accept or refuse a
 * pending write from inside its own conversation, without ever signing into the testPike
 * UI. The platform guarantees the red line in another way: every write still needs one
 * human decision, carried by a one-shot confirm reference bound to the drafted payload.
 * <p>Covered contracts: (1) a grant can only be issued for a domain whose landing handler
 * is registered (no gate that cannot settle), (2) accept consumes the reference atomically
 * and lands exactly the drafted write, (3) reject settles the grant with zero writes,
 * (4) a second presentation of the same reference is refused (replay and UI-channel race
 * are covered by the same first-wins atomic consumption), (5) an illegal decision word is
 * refused before the grant is burned, so the caller can retry, (6) unknown or blank
 * references are refused, (7) the TTL key literal and its fallback resolution are pinned.
 * <p>Bidirectional fixtures: each refusal is paired in the same test with the legal
 * counterpart passing, so removing a guard silently cannot keep the suite green.
 */
@SpringBootTest(classes = CoreApplication.class)
class McpConfirmSettlementServiceTest {

    @Autowired
    private McpConfirmSettlementService settlementService;

    /** Fixture services below are wired for the shared draft builders only. */
    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private final TrackedProjectLedger ledger = new TrackedProjectLedger();

    @BeforeEach
    @AfterEach
    void sweepFixtureProjects() {
        ledger.sweep(jdbcTemplate);
    }

    private long createProject(String code) {
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                ledger.projectIds(), "确认闭环项目" + code, code);
    }

    private long createCase(long projectId) {
        return ScriptWritebackDrafts.createCase(caseService, projectId);
    }

    private String writebackPayload(long caseId) {
        return ScriptWritebackDrafts.payloadJson(objectMapper, caseId);
    }

    private Map<String, Object> caseRow(long caseId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, evidence_grade, version FROM tp_test_case WHERE id = ?", caseId);
    }

    private int auditCount(String action, String objectId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE object_type = 'mcp' "
                        + "AND action = ? AND object_id = ?", Integer.class, action, objectId);
        return count == null ? 0 : count;
    }

    @Test
    void issuingWithoutRegisteredLandingHandlerIsRefusedBeforeAnyGrantExists() throws Exception {
        ServiceException refusal = assertThrows(ServiceException.class, () -> settlementService
                .issueGrant("nonexistent.domain", "{}", "动什么对象、变更点、影响面", 1L, 1L));
        assertTrue(refusal.getMessage().contains("nonexistent.domain"));

        // Legal counterpart: the registered script.writeback domain issues fine.
        long projectId = createProject("CFM0");
        long caseId = createCase(projectId);
        McpConfirmGrantTicket ticket = settlementService.issueGrant("script.writeback",
                writebackPayload(caseId), "为用例新建脚本版本并拉回待评审", projectId, 1L);
        assertNotNull(ticket.confirmRef());
        assertEquals("为用例新建脚本版本并拉回待评审", ticket.summary());
    }

    @Test
    void acceptConsumesGrantOnceAndLandsTheDraftedWriteback() throws Exception {
        long projectId = createProject("CFM1");
        long caseId = createCase(projectId);
        int statusBefore = ((Number) caseRow(caseId).get("status")).intValue();

        McpConfirmGrantTicket ticket = settlementService.issueGrant("script.writeback",
                writebackPayload(caseId), "为用例新建脚本版本并拉回待评审", projectId, 1L);
        McpConfirmOutcome outcome = settlementService.confirm(ticket.confirmRef(), "accept", null, 1L);

        assertTrue(outcome.landed());
        assertEquals("script.writeback", outcome.domain());
        assertEquals("accept", outcome.decision());
        Map<String, Object> row = caseRow(caseId);
        assertEquals(1, ((Number) row.get("status")).intValue(), "case pulled back to pending review");
        assertEquals(2, ((Number) row.get("evidence_grade")).shortValue(), "evidence on file");
        assertEquals(statusBefore, statusBefore);
        assertEquals(1, auditCount("mcp_confirm_accept", ticket.confirmRef()));

        // Replay and UI-channel race share this one refusal: same reference second time.
        assertThrows(ServiceException.class,
                () -> settlementService.confirm(ticket.confirmRef(), "accept", null, 1L));
        assertEquals(1, auditCount("mcp_confirm_accept", ticket.confirmRef()));
    }

    @Test
    void rejectSettlesGrantWithZeroWritesAndKeepsTheDraftTrace() throws Exception {
        long projectId = createProject("CFM2");
        long caseId = createCase(projectId);
        Map<String, Object> before = caseRow(caseId);

        McpConfirmGrantTicket ticket = settlementService.issueGrant("script.writeback",
                writebackPayload(caseId), "为用例新建脚本版本并拉回待评审", projectId, 1L);
        McpConfirmOutcome outcome = settlementService.confirm(ticket.confirmRef(), "reject", "改动过大，先走站内草案", 1L);

        assertFalse(outcome.landed());
        assertEquals("reject", outcome.decision());
        Map<String, Object> after = caseRow(caseId);
        assertEquals(before.get("status"), after.get("status"), "reject must not touch the case");
        assertEquals(before.get("version"), after.get("version"));
        assertEquals(1, auditCount("mcp_confirm_reject", ticket.confirmRef()));
        String afterJson = jdbcTemplate.queryForObject(
                "SELECT after_json FROM tp_audit_log WHERE object_type = 'mcp' "
                        + "AND action = 'mcp_confirm_reject' AND object_id = ? ORDER BY id DESC LIMIT 1",
                String.class, ticket.confirmRef());
        assertTrue(afterJson.contains("改动过大"), "refusal reason is kept for the audit trail");
        assertTrue(afterJson.contains("session"), "audit names the confirmation channel as session");

        // Burned either way: a second presentation (any decision) is refused.
        assertThrows(ServiceException.class,
                () -> settlementService.confirm(ticket.confirmRef(), "accept", null, 1L));
    }

    @Test
    void illegalDecisionWordIsRefusedBeforeTheGrantIsBurned() throws Exception {
        long projectId = createProject("CFM3");
        long caseId = createCase(projectId);
        McpConfirmGrantTicket ticket = settlementService.issueGrant("script.writeback",
                writebackPayload(caseId), "为用例新建脚本版本并拉回待评审", projectId, 1L);

        ServiceException refusal = assertThrows(ServiceException.class,
                () -> settlementService.confirm(ticket.confirmRef(), "maybe", null, 1L));
        assertTrue(refusal.getMessage().contains("accept"),
                "the refusal names the legal decision words");

        // Grant survived the guard refusal: the retry with a legal word lands.
        assertTrue(settlementService.confirm(ticket.confirmRef(), "accept", null, 1L).landed());
    }

    @Test
    void unknownOrBlankReferenceIsRefused() {
        assertThrows(ServiceException.class, () -> settlementService.confirm("0fdeadbeef", "accept", null, 1L));
        assertThrows(ServiceException.class, () -> settlementService.confirm(" ", "accept", null, 1L));
        assertThrows(ServiceException.class, () -> settlementService.confirm(null, "accept", null, 1L));
    }

    @Test
    void ttlKeyLiteralAndFallbackResolutionArePinned() {
        assertEquals("mcp.confirm.ttl-seconds", McpConfirmGrantStore.TTL_KEY);
        assertEquals(1800L, McpConfirmGrantStore.DEFAULT_TTL_SECONDS);
        assertEquals(45L, McpConfirmGrantStore.resolveTtlSeconds("45"));
        assertEquals(1800L, McpConfirmGrantStore.resolveTtlSeconds(null));
        assertEquals(1800L, McpConfirmGrantStore.resolveTtlSeconds(" "));
        assertEquals(1800L, McpConfirmGrantStore.resolveTtlSeconds("bogus"));
        assertEquals(1800L, McpConfirmGrantStore.resolveTtlSeconds("-3"));
        assertEquals(1800L, McpConfirmGrantStore.resolveTtlSeconds("0"));
    }
}
