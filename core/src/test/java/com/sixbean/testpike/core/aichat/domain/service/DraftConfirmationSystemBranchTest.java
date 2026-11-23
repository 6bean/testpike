package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.api.dto.DraftApplyResult;
import com.sixbean.testpike.core.aichat.api.dto.DraftTicket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System-origin draft branch of the shared confirmation door .
 * <p>Distilled project-memory proposals land without a chat session: the row
 * carries session_id=NULL plus a project anchor, so the door cannot walk the
 * session-ownership guard. It must instead prove, in fixed order: a human
 * login identity (40301) → the draft really anchors to a project (validation
 * refusal, a dangling row confirms nothing) → project membership through the
 * single-sourced {@code ProjectAccessService.requireProjectMember} door
 * (40301, same door the case ledger uses — no second membership check) →
 * the project-memory permission key from {@code RoleService.permsOfUser}
 * (40301, refusal names the key). Ticket issue/consume semantics stay exactly
 * as the session branch has them (one-shot Redis ticket, auditable issue).
 * <p>The ctx_memory write strategy registered in lands through this
 * same door: apply must prove the full guard chain first, then promote the
 * awaiting-confirmation memory row — the positive spectrum asserted below.
 * Real dev database, zero mocks (same posture as DraftConfirmationChainTest).
 */
@SpringBootTest(classes = CoreApplication.class)
class DraftConfirmationSystemBranchTest {

    /** Draft kind distilled project memory uses (ctx_memory strategy lands in . */
    private static final String KIND_CTX_MEMORY = "ctx_memory";

    /** Permission key guarding the project-memory ledger (catalog form). */
    private static final String MEMORY_PERM_KEY = "perm:aichat:project-memory";

    @Autowired
    private DraftConfirmationService confirmationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String tag;
    private long projectId;
    private long draftId;
    /** A project member holding the memory key (admin template = full catalog). */
    private long keyedMemberId;
    /** A project member whose role grants no permission keys. */
    private long keylessMemberId;
    /** A key-holding user who is NOT a member of the draft's project. */
    private long outsiderKeyId;
    /** Audit high-water: window per-run rows like the chain suite does. */
    private long auditBaselineId;

    @BeforeEach
    void setUp() {
        auditBaselineId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(max(id), 0) FROM tp_audit_log", Long.class);
        tag = "t512b" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        projectId = insertProject();
        keyedMemberId = insertUser("keyed", 4);   // legacy admin → allKeys covers the memory key
        keylessMemberId = insertUser("keyless", 9); // outside the legacy mapping → empty permission set
        outsiderKeyId = insertUser("outsider", 4);
        grantMembership(keyedMemberId);
        grantMembership(keylessMemberId);
        draftId = insertSystemDraft(projectId);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM tp_ai_chat_draft WHERE id = ?", draftId);
        jdbcTemplate.update("DELETE FROM tp_ai_ctx_memory WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE user_id IN (?, ?, ?)",
                keyedMemberId, keylessMemberId, outsiderKeyId);
        // Audit rows stay: tp_audit_log is append-only by trigger (retention
        // cleanup owns deletion), and the suite counts them windowed by id.
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_user WHERE id IN (?, ?, ?)",
                keyedMemberId, keylessMemberId, outsiderKeyId);
    }

    // ── fixtures (same shape the chain suite builds, minus the session) ──

    private long insertProject() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, tag, tag);
    }

    private long insertUser(String who, int legacyRole) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, '$2a$10$x', ?, ?, 1) RETURNING id
                """, Long.class, tag + who, tag + who, legacyRole);
    }

    private void grantMembership(long userId) {
        jdbcTemplate.update(
                "INSERT INTO tp_user_project (project_id, user_id, role) VALUES (?, ?, 1)",
                projectId, userId);
    }

    private long insertSystemDraft(Long anchorProjectId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_ai_chat_draft
                    (session_id, message_id, project_id, object_type, payload_json, status)
                VALUES (NULL, NULL, ?, ?, '{"content":"distilled memory fact"}'::jsonb, 1)
                RETURNING id
                """, Long.class, anchorProjectId, KIND_CTX_MEMORY);
    }

    private int auditCount(String action, long draft) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE action = ? AND object_id = ? AND id > ?",
                Integer.class, action, String.valueOf(draft), auditBaselineId);
        return n == null ? 0 : n;
    }

    // ── guard spectrum ──

    @Test
    void anonymousIssueOnSystemDraftIsRefusedAsHumanOnlyDoor() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> confirmationService.issueConfirmToken(null, draftId));
        assertEquals(BizErrorCode.FORBIDDEN, e.getCode(),
                "a session-less draft still needs a login identity before anything else");
    }

    @Test
    void systemDraftWithoutProjectAnchorIsRefused() {
        long dangling = insertSystemDraft(null);
        try {
            ServiceException e = assertThrows(ServiceException.class,
                    () -> confirmationService.issueConfirmToken(keyedMemberId, dangling));
            assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode(),
                    "an unanchored system draft governs no project — refuse instead of NPE");
            assertEquals(0, auditCount("draft-confirm-issue", dangling),
                    "refusals leave no ticket and no audit trail");
        } finally {
            jdbcTemplate.update("DELETE FROM tp_ai_chat_draft WHERE id = ?", dangling);
        }
    }

    @Test
    void keyHoldingNonMemberIsRefusedByTheMembershipDoor() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> confirmationService.issueConfirmToken(outsiderKeyId, draftId));
        assertEquals(BizErrorCode.FORBIDDEN, e.getCode(),
                "horizontal access: keys without membership still do not open the door");
    }

    @Test
    void memberWithoutMemoryKeyIsRefusedAndRefusalNamesTheKey() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> confirmationService.issueConfirmToken(keylessMemberId, draftId));
        assertEquals(BizErrorCode.FORBIDDEN, e.getCode());
        assertTrue(e.getMessage().contains(MEMORY_PERM_KEY),
                "the refusal must point at the exact permission to grant, same shape as the admin edge");
    }

    @Test
    void guardedMemberIssueLandsTicketAndAuditsUnderTheDraftProject() {
        DraftTicket ticket = confirmationService.issueConfirmToken(keyedMemberId, draftId);
        assertNotNull(ticket.token());
        assertTrue(ticket.expiresInSeconds() > 0);
        assertEquals(1, auditCount("draft-confirm-issue", draftId),
                "issuing stays auditable exactly like the session branch");
        Map<String, Object> audit = jdbcTemplate.queryForMap(
                "SELECT project_id, operator_id FROM tp_audit_log WHERE action = 'draft-confirm-issue'"
                        + " AND object_id = ? AND id > ?", String.valueOf(draftId), auditBaselineId);
        assertEquals(projectId, ((Number) audit.get("project_id")).longValue(),
                "system drafts audit under their project anchor, not a session's project");
        assertEquals(keyedMemberId, ((Number) audit.get("operator_id")).longValue());
    }

    @Test
    void guardsOpenForTheMemberThenApplyLandsThePendingMemoryRow() {
        // flip: the ctx_memory strategy is registered, so a guarded
        // member's apply walks through the door and promotes the distilled
        // row awaiting-confirmation -> active (the dispatch-gate refusal of
        // is now the positive spectrum asserted here).
        insertPendingMemory("distilled memory fact");
        DraftTicket ticket = confirmationService.issueConfirmToken(keyedMemberId, draftId);
        DraftApplyResult applied = confirmationService.apply(
                keyedMemberId, draftId, ticket.token());
        assertEquals(KIND_CTX_MEMORY, applied.objectType());

        int status = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_ai_chat_draft WHERE id = ?", Integer.class, draftId);
        assertEquals(2, status, "the ledger row flips to applied after the write lands");
        int active = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_ctx_memory WHERE project_id = ? AND content = ?"
                        + " AND inject_status = 1", Integer.class, projectId, "distilled memory fact");
        assertEquals(1, active, "the distilled entry is only injectable after human confirmation");

        ServiceException replay = assertThrows(ServiceException.class,
                () -> confirmationService.apply(keyedMemberId, draftId, ticket.token()));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, replay.getCode(),
                "the ledger row already flipped to applied: the replayed door closes at the "
                        + "pending-guard (40904) before the spent one-shot ticket is touched");
    }

    @Test
    void applyWithoutAPendingMemoryRowRefusesAndKeepsTheDraftConfirmable() {
        // The underlying awaiting row can vanish (recycle cleanup between
        // distillation and confirmation): the strategy must refuse loudly and
        // leave the ledger draft pending so a re-distilled entry can confirm.
        DraftTicket ticket = confirmationService.issueConfirmToken(keyedMemberId, draftId);
        ServiceException e = assertThrows(ServiceException.class,
                () -> confirmationService.apply(keyedMemberId, draftId, ticket.token()));
        assertEquals(BizErrorCode.NOT_FOUND, e.getCode(),
                "no awaiting row = nothing to promote — refuse instead of half-applying");
        int status = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_ai_chat_draft WHERE id = ?", Integer.class, draftId);
        assertEquals(1, status, "a failed landing leaves the draft confirmable");
    }

    /** Awaiting-confirmation memory row as distillation would write it. */
    private void insertPendingMemory(String content) {
        jdbcTemplate.update(
                "INSERT INTO tp_ai_ctx_memory (project_id, content, source_ref, inject_status)"
                        + " VALUES (?, ?, '[]'::jsonb, 2)",
                projectId, content);
    }
}
