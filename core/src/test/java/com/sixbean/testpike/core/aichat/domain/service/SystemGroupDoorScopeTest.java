package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.api.dto.DraftGroupApplyResult;
import com.sixbean.testpike.core.aichat.api.dto.DraftTicket;
import com.sixbean.testpike.core.aichat.domain.component.DraftGroupScope;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System-scope group door : the group ticket and the
 * pending-card resolution generalize from "one answer message" to the
 * DraftGroupScope wire, so grouped-draft impacts of the linkage bus — which
 * land as system-origin drafts (session/message NULL, project anchored,
 * V363 posture) — open through the SAME door machinery as dialog answers.
 * Rowset resolution is by ledger columns only (project + kind + system
 * origin), never by a copied grouping table: a dialog card of the same
 * project and a system card of another project must stay outside the group.
 * Guard order is untouched (red line 6 + door invariants): parse -> rows ->
 * per-row origin-aware guards -> one-shot scope-bound ticket -> the same
 * phase A/B landing as the message door. The system kind exercised here is
 * ctx_memory, the ledger's own system-origin kind — the point proven is
 * the DOOR shape, not a per-kind write path. Real chain, zero mocks, real
 * dev database; every row is deleted by collected id.
 */
@SpringBootTest(classes = CoreApplication.class)
class SystemGroupDoorScopeTest {

    private static final String KIND_CTX_MEMORY = "ctx_memory";

    @Autowired
    private DraftConfirmationService confirmationService;

    @Autowired
    private ChatSessionService sessionService;

    @Autowired
    private DraftCardMinting minting;

    @Autowired
    private DraftLedgerCleanup cleanup;

    @Autowired
    private CaseService caseService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String tag;
    private long projectId;
    private long otherProjectId;
    private long userId;
    private long sessionId;
    private int projectSeq;
    private final List<Long> createdCaseIds = new ArrayList<>();
    private final List<Long> createdDraftIds = new ArrayList<>();
    private final List<Long> createdMemoryIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        tag = "sysscope" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        userId = insertUser(tag);
        projectId = insertProject();
        otherProjectId = insertProject();
        jdbcTemplate.update(
                "INSERT INTO tp_user_project (project_id, user_id, role) VALUES (?, ?, 1)",
                projectId, userId);
        ChatSessionInfo session = sessionService.create(projectId, userId,
                "System group door " + tag, 1, "{\"route\":\"/cases\"}");
        sessionId = session.id();
    }

    @AfterEach
    void tearDown() {
        for (Long draftId : createdDraftIds) {
            jdbcTemplate.update("DELETE FROM tp_ai_chat_draft WHERE id = ?", draftId);
        }
        for (Long memoryId : createdMemoryIds) {
            jdbcTemplate.update("DELETE FROM tp_ai_ctx_memory WHERE id = ?", memoryId);
        }
        cleanup.sessionChain(sessionId);
        cleanup.caseCascade(createdCaseIds);
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id IN (?, ?)",
                projectId, otherProjectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", otherProjectId);
        jdbcTemplate.update("DELETE FROM tp_user WHERE id = ?", userId);
    }

    private long insertUser(String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, '$2a$10$x', ?, 1, 1) RETURNING id
                """, Long.class, name, name);
    }

    private long insertProject() {
        // tp_project.code is globally unique — every seeded project needs its
        // own code, so a monotonically local counter keeps the pair distinct.
        projectSeq++;
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, tag + "p" + projectSeq, tag + "p" + projectSeq);
    }

    private long seedCase(String title) {
        CaseInfo created = caseService.create(userId, new CaseCreateInput(projectId, title,
                "pre", "expect", 1, 2, "web", null, 2, 1, null, List.of()));
        createdCaseIds.add(created.id());
        return created.id();
    }

    /**
     * System-origin ctx_memory card: the exact ledger posture (V363 shape —
     * session/message NULL, project anchored) the linkage bus will mint its
     * grouped_draft cards in; the distilled row it promotes is seeded first
     * (inject_status 2 = awaiting the human gate).
     */
    private long systemMemoryDraft(long anchorProjectId, String fact) {
        Long memoryId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_ai_ctx_memory (project_id, content, source_ref, inject_status)
                VALUES (?, ?, '[]'::jsonb, 2) RETURNING id
                """, Long.class, anchorProjectId, fact);
        createdMemoryIds.add(memoryId);
        Long draftId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_ai_chat_draft
                    (session_id, message_id, project_id, object_type, summary,
                     schema_version, payload_json, status)
                VALUES (NULL, NULL, ?, 'ctx_memory', ?, '1',
                        CAST(? AS jsonb), 1)
                RETURNING id
                """, Long.class, anchorProjectId, "system group " + fact,
                "{\"content\":\"" + fact + "\",\"sourceRef\":\"[]\"}");
        createdDraftIds.add(draftId);
        return draftId;
    }

    private int statusOf(long draftId) {
        return ((Number) jdbcTemplate.queryForObject(
                "SELECT status FROM tp_ai_chat_draft WHERE id = ?", Integer.class, draftId))
                .intValue();
    }

    private int memoryStatus(long anchorProjectId, String fact) {
        Integer status = jdbcTemplate.queryForObject(
                "SELECT inject_status FROM tp_ai_ctx_memory WHERE project_id = ? AND content = ?",
                Integer.class, anchorProjectId, fact);
        return status == null ? -1 : status;
    }

    private String sysWire(long project, String kind) {
        return DraftGroupScope.forSystem(project, kind).wire();
    }

    private String sysWire(long project) {
        return sysWire(project, KIND_CTX_MEMORY);
    }

    @Test
    void systemScopeDoorResolvesOnlyProjectKindSystemCards() {
        String factA = "sys group fact A " + tag;
        String factB = "sys group fact B " + tag;
        long cardA = systemMemoryDraft(projectId, factA);
        long cardB = systemMemoryDraft(projectId, factB);
        long caseC = seedCase("sys group dialog sibling " + tag);
        long dialogCard = minting.caseWriteDraftInMessage(sessionId,
                minting.openAssistantMessage(sessionId), caseC,
                caseService.get(caseC).version(), "{\"title\":\"dialog card\"}");

        DraftTicket ticket = confirmationService.issueGroupConfirmTokenByScope(userId,
                sysWire(projectId));
        DraftGroupApplyResult result = confirmationService.applyGroupByScope(userId,
                sysWire(projectId), ticket.token(), List.of(cardA));

        assertEquals(sysWire(projectId), result.groupScope());
        assertEquals(0, result.messageId(), "a system-scoped receipt carries no fake message id");
        assertEquals(2, result.items().size(),
                "the system group is exactly this project's pending system cards of the kind");
        assertEquals("landed", result.items().stream()
                .filter(i -> i.draftId() == cardA).findFirst().orElseThrow().outcome());
        assertEquals(2, statusOf(cardA));
        assertEquals(1, statusOf(cardB), "an unticked card of the group stays pending");
        assertEquals(1, statusOf(dialogCard),
                "a dialog card of the same project never joins the system group");
        assertEquals(1, memoryStatus(projectId, factA),
                "the confirmed card promoted its distilled row through the same door");
    }

    @Test
    void crossScopeTicketCannotOpenTheOtherDoor() {
        String factA = "cross sys fact " + tag;
        long cardA = systemMemoryDraft(projectId, factA);
        long caseC = seedCase("cross dialog " + tag);
        long message = minting.openAssistantMessage(sessionId);
        long dialogCard = minting.caseWriteDraftInMessage(sessionId, message, caseC,
                caseService.get(caseC).version(), "{\"title\":\"cross dialog card\"}");

        DraftTicket messageTicket = confirmationService.issueGroupConfirmToken(userId, message);
        assertThrows(ServiceException.class, () -> confirmationService.applyGroupByScope(
                userId, sysWire(projectId), messageTicket.token(), List.of(cardA)),
                "a dialog group ticket must not open the system door");
        assertEquals(1, statusOf(cardA), "the refused system card stays pending");

        DraftTicket systemTicket = confirmationService.issueGroupConfirmTokenByScope(userId,
                sysWire(projectId));
        assertThrows(ServiceException.class, () -> confirmationService.applyGroup(
                userId, message, systemTicket.token(), List.of(dialogCard)),
                "a system group ticket must not open the dialog door");
        assertEquals(1, statusOf(dialogCard), "the refused dialog card stays pending");
        assertEquals(2, memoryStatus(projectId, factA),
                "a ticket bound to the other group wrote nothing here");
    }

    @Test
    void selectionOutsideTheSystemGroupIsRefusedWithoutBurningTheTicket() {
        String factA = "shape mine " + tag;
        String factD = "shape foreign " + tag;
        long cardA = systemMemoryDraft(projectId, factA);
        long cardD = systemMemoryDraft(otherProjectId, factD);
        DraftTicket ticket = confirmationService.issueGroupConfirmTokenByScope(userId,
                sysWire(projectId));
        assertThrows(ServiceException.class, () -> confirmationService.applyGroupByScope(
                userId, sysWire(projectId), ticket.token(), List.of(cardD)),
                "a card outside the resolved group is a shape error before the one-shot consume");
        assertEquals(1, statusOf(cardA));
        // The unspent ticket still opens its own group afterwards.
        DraftGroupApplyResult retry = confirmationService.applyGroupByScope(userId,
                sysWire(projectId), ticket.token(), List.of(cardA));
        assertEquals("landed", retry.items().get(0).outcome());
    }

    @Test
    void malformedOrEmptySystemGroupIsRefusedBeforeAnyWrite() {
        String factA = "guard fact " + tag;
        systemMemoryDraft(projectId, factA);
        assertThrows(ServiceException.class, () -> confirmationService
                .issueGroupConfirmTokenByScope(userId, "group:7"));
        assertThrows(ServiceException.class, () -> confirmationService
                .issueGroupConfirmTokenByScope(userId, "system:7:bad-kind-x"));
        assertThrows(ServiceException.class, () -> confirmationService
                .issueGroupConfirmTokenByScope(userId, "system:-3:CASE_WRITE"));
        assertThrows(ServiceException.class, () -> confirmationService
                .issueGroupConfirmTokenByScope(userId, "message:0"));
        long ghostProject = insertProject();
        try {
            ServiceException empty = assertThrows(ServiceException.class, () -> confirmationService
                    .issueGroupConfirmTokenByScope(userId, sysWire(ghostProject)));
            assertTrue(empty.getMessage().contains("待确认"),
                    "an empty group refuses loudly, same posture as the dialog door");
        } finally {
            jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", ghostProject);
        }
    }

    @Test
    void nonMemberCannotOpenTheSystemGroupDoor() {
        String factA = "outsider fact " + tag;
        long cardA = systemMemoryDraft(projectId, factA);
        long outsider = insertUser(tag + "x");
        try {
            assertThrows(ServiceException.class, () -> confirmationService
                    .issueGroupConfirmTokenByScope(outsider, sysWire(projectId)),
                    "membership is re-checked per row on the scope path too (red line 6)");
            assertEquals(1, statusOf(cardA));
        } finally {
            jdbcTemplate.update("DELETE FROM tp_user WHERE id = ?", outsider);
        }
    }
}
