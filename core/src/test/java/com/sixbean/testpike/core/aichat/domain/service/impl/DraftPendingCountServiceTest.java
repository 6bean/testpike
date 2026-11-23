package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.domain.service.DraftLedgerCleanup;
import com.sixbean.testpike.core.aichat.domain.service.DraftPendingCountService;
import com.sixbean.testpike.core.aichat.domain.service.LedgerSessionSeed;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pending-change ledger count behind the pinned-object panel (AM batch
 * ; 02 GET /api/v1/ai/chat/drafts/pending-count). The panel answers
 * "how many proposals about this object still wait for a human decision",
 * so the count includes the stale-with-regenerate-exit state and excludes
 * everything already decided (applied/voided).
 * <p>Scope discipline: project x object-kind x target-entity. A dialog
 * draft's project comes through its owning session (the draft row keeps
 * project_id NULL there, V363 posture), while a system-origin draft anchors
 * project_id directly — both branches must land in the same count.
 * Real dev database, zero mocks; every row is deleted by collected id.
 */
@SpringBootTest(classes = CoreApplication.class)
class DraftPendingCountServiceTest {

    private static final String KIND_CASE = "CASE_WRITE";
    private static final String KIND_REQUIREMENT = "REQUIREMENT_WRITE";

    @Autowired
    private DraftPendingCountService pendingCountService;

    @Autowired
    private LedgerSessionSeed sessionSeed;

    @Autowired
    private DraftLedgerCleanup ledgerCleanup;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private LedgerSessionSeed.Scope scope;
    private long otherProjectId;
    private long targetCaseId;
    private final List<Long> draftIds = new ArrayList<>();

    @BeforeEach
    void seedLedger() {
        scope = sessionSeed.open("t490p", "pending-count spectrum", "{\"route\":\"/cases\"}");
        otherProjectId = insertProjectRow();
        targetCaseId = 900000L + Math.abs(scope.tag().hashCode() % 100000);
        // In scope: one pending + one stale dialog draft (session-derived project),
        // one pending system-origin draft (direct project anchor).
        draftIds.add(insertDraft(scope.sessionId(), null, KIND_CASE, targetCaseId,
                DraftConfirmationServiceImpl.STATUS_PENDING));
        draftIds.add(insertDraft(scope.sessionId(), null, KIND_CASE, targetCaseId,
                DraftConfirmationServiceImpl.STATUS_STALE));
        draftIds.add(insertDraft(null, scope.projectId(), KIND_CASE, targetCaseId,
                DraftConfirmationServiceImpl.STATUS_PENDING));
        // Out of scope: decided states, other target, other kind, other project.
        draftIds.add(insertDraft(scope.sessionId(), null, KIND_CASE, targetCaseId,
                DraftConfirmationServiceImpl.STATUS_APPLIED));
        draftIds.add(insertDraft(scope.sessionId(), null, KIND_CASE, targetCaseId,
                DraftConfirmationServiceImpl.STATUS_VOIDED));
        draftIds.add(insertDraft(scope.sessionId(), null, KIND_CASE, targetCaseId + 1,
                DraftConfirmationServiceImpl.STATUS_PENDING));
        draftIds.add(insertDraft(scope.sessionId(), null, KIND_REQUIREMENT, targetCaseId,
                DraftConfirmationServiceImpl.STATUS_PENDING));
        draftIds.add(insertDraft(null, otherProjectId, KIND_CASE, targetCaseId,
                DraftConfirmationServiceImpl.STATUS_PENDING));
    }

    @AfterEach
    void removeRows() {
        for (long id : draftIds) {
            jdbcTemplate.update("DELETE FROM tp_ai_chat_draft WHERE id = ?", id);
        }
        ledgerCleanup.sessionChain(scope.sessionId());
        jdbcTemplate.update("DELETE FROM tp_ai_chat_draft WHERE project_id IN (?, ?)",
                scope.projectId(), otherProjectId);
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE user_id = ?", scope.userId());
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", scope.projectId());
        jdbcTemplate.update("DELETE FROM tp_user WHERE id = ?", scope.userId());
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", otherProjectId);
    }

    @Test
    void countsOnlyUndecidedDraftsOfTheSameObject() {
        assertEquals(3, pendingCountService.countPending(scope.projectId(), KIND_CASE, targetCaseId),
                "pending + stale + system-origin pending count; applied/voided never do");
    }

    @Test
    void eachRowCountsOnlyInsideItsOwnScope() {
        // The target-separation row belongs to the neighbour target and the
        // kind-separation row to the requirement kind: neither inflated the
        // main count above (3, not 5), and each counts for its own scope only.
        assertEquals(1, pendingCountService.countPending(scope.projectId(), KIND_CASE, targetCaseId + 1));
        assertEquals(1, pendingCountService.countPending(scope.projectId(), KIND_REQUIREMENT, targetCaseId));
    }

    @Test
    void panelChannelCountsByChatKindVocabulary() {
        // The pinned-object panel sends the upper-cased chat
        // reference kind, not the draft ledger's wireName; without a
        // single-source translation at this read door the badge would sit at
        // 0 forever while three cards wait behind the confirmation door.
        assertEquals(3, pendingCountService.countPending(scope.projectId(), "CASE", targetCaseId),
                "chat kind CASE resolves to the CASE_WRITE ledger rows");
        // Unregistered kinds stay honest: no draft family owns them, so the
        // raw equality match finds nothing instead of inventing a count.
        assertEquals(0, pendingCountService.countPending(scope.projectId(), "TOOL", targetCaseId));
    }

    @Test
    void otherProjectsAnchorRowCountsForThatProjectOnly() {
        assertEquals(1, pendingCountService.countPending(otherProjectId, KIND_CASE, targetCaseId),
                "the system-anchored row belongs to the other project; the main count (3) proves it never leaks here");
    }

    private long insertProjectRow() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, scope.tag() + "-o", scope.tag() + "-o");
    }

    private long insertDraft(Long sessionId, Long projectId, String objectType,
                             long targetId, int status) {
        Integer id = jdbcTemplate.queryForObject("""
                INSERT INTO tp_ai_chat_draft
                    (session_id, message_id, project_id, object_type, target_id,
                     payload_json, status)
                VALUES (?, NULL, ?, ?, ?, '{"content":"pending-count fixture"}', ?)
                RETURNING id
                """, Integer.class, sessionId, projectId, objectType, targetId, status);
        return id;
    }
}
