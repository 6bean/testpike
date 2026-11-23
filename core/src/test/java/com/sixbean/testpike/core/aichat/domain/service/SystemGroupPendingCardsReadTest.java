package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.api.dto.PendingGroupCardView;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Read side of the system-scope group :
 * the pinned panel needs the pending cards of one project + object kind as
 * a LIST before any ticket exists, so the same rowset the group door walks
 * must be readable without burning anything. The read keeps every guard of
 * the issue door — origin-aware per-row ownership, strict scope parse —
 * and differs in exactly one respect: an empty group reads as an empty
 * list instead of refusing, because a panel legitimately shows "nothing
 * pending". Rows outside the scope (a dialog card of the same project, a
 * system card of another project) never appear. Real chain, zero mocks,
 * real slot database; every seeded row is deleted by collected id.
 */
@SpringBootTest(classes = CoreApplication.class)
class SystemGroupPendingCardsReadTest {

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
    private long outsiderId;
    private long sessionId;
    private int projectSeq;
    private final List<Long> createdCaseIds = new ArrayList<>();
    private final List<Long> createdDraftIds = new ArrayList<>();
    private final List<Long> createdMemoryIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        tag = "pendread" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        userId = insertUser(tag);
        outsiderId = insertUser("out" + tag);
        projectId = insertProject();
        otherProjectId = insertProject();
        jdbcTemplate.update(
                "INSERT INTO tp_user_project (project_id, user_id, role) VALUES (?, ?, 1)",
                projectId, userId);
        ChatSessionInfo session = sessionService.create(projectId, userId,
                "Pending cards read " + tag, 1, "{\"route\":\"/cases\"}");
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
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", otherProjectId);
        jdbcTemplate.update("DELETE FROM tp_user WHERE id IN (?, ?)", userId, outsiderId);
    }

    private long insertUser(String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, '$2a$10$x', ?, 1, 1) RETURNING id
                """, Long.class, name, name);
    }

    private long insertProject() {
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

    /** System-origin card of the exact V363 ledger posture (see the scope-door test). */
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
                """, Long.class, anchorProjectId, "pending read " + fact,
                "{\"content\":\"" + fact + "\",\"sourceRef\":\"[]\"}");
        createdDraftIds.add(draftId);
        return draftId;
    }

    private String sysWire(long anchorProjectId) {
        return DraftGroupScope.forSystem(anchorProjectId, KIND_CTX_MEMORY).wire();
    }

    /**
     * A system-origin pending card of any object kind written straight onto
     * the ledger columns the bus uses (D4d): the linkage bus mints
     * SCRIPT_REPAIR cards with session/message NULL, project pinned — the
     * read side must enumerate every such kind without the caller naming it.
     */
    private long systemLedgerDraft(long anchorProjectId, String kind, String fact,
                                   Long targetId) {
        Long draftId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_ai_chat_draft
                    (session_id, message_id, project_id, object_type, target_id, summary,
                     schema_version, payload_json, status)
                VALUES (NULL, NULL, ?, ?, ?, ?, '1',
                        CAST(? AS jsonb), 1)
                RETURNING id
                """, Long.class, anchorProjectId, kind, targetId, "pending read " + fact,
                "{\"content\":\"" + fact + "\",\"sourceRef\":\"[]\"}");
        createdDraftIds.add(draftId);
        return draftId;
    }

    @Test
    void enumeratedReadListsEveryNonEmptySystemGroup() {
        long repair = systemLedgerDraft(projectId, "SCRIPT_REPAIR",
                "pending read repair fact " + tag, seedCase("pending read repair host " + tag));
        long memory = systemLedgerDraft(projectId, KIND_CTX_MEMORY,
                "pending read memory fact " + tag, null);
        systemLedgerDraft(otherProjectId, "SCRIPT_REPAIR", "pending read foreign " + tag, null);
        long caseId = seedCase("pending read dialog sibling " + tag);
        minting.caseWriteDraftInMessage(sessionId,
                minting.openAssistantMessage(sessionId), caseId,
                caseService.get(caseId).version(), "{\"title\":\"dialog card\"}");

        List<PendingGroupCardView> rows =
                confirmationService.pendingGroupCards(userId, "system:" + projectId);

        assertEquals(2, rows.size(),
                "the enumeration is exactly this project's pending system groups,"
                        + " no dialog card and no foreign project row");
        assertEquals(repair, rows.get(0).draftId(),
                "groups come back ordered by object kind, cards in issue order");
        assertEquals(memory, rows.get(1).draftId());
        assertEquals("SCRIPT_REPAIR", rows.get(0).objectType(),
                "the card carries its own group token so the panel needs no vocabulary");
        assertEquals(KIND_CTX_MEMORY, rows.get(1).objectType());
        assertEquals(projectId, rows.get(0).projectId());
        assertEquals(1, rows.get(0).status(), "every listed row is still pending");
    }

    @Test
    void enumeratedReadOfNoPendingGroupIsEmptyList() {
        systemLedgerDraft(otherProjectId, "SCRIPT_REPAIR", "pending read foreign " + tag, null);
        assertTrue(confirmationService.pendingGroupCards(userId, "system:" + projectId).isEmpty(),
                "a project with nothing pending is a legitimate enumeration, not an error");
    }

    @Test
    void outsiderCannotReadTheEnumeratedGroups() {
        systemLedgerDraft(projectId, "SCRIPT_REPAIR", "pending read guarded " + tag, null);
        assertThrows(ServiceException.class,
                () -> confirmationService.pendingGroupCards(outsiderId, "system:" + projectId),
                "the per-row ownership guard applies to the enumeration too");
    }

    @Test
    void enumeratedScopeIsRefusedByTheIssueDoor() {
        systemLedgerDraft(projectId, "SCRIPT_REPAIR", "pending read ticket bait " + tag, null);
        ServiceException refusal = assertThrows(ServiceException.class,
                () -> confirmationService.issueGroupConfirmTokenByScope(userId,
                        "system:" + projectId),
                "a ticket binds ONE group; the read-all enumeration never opens the door");
        assertEquals(BizErrorCode.VALIDATION_ERROR, refusal.getCode(),
                "shape refusal, not an empty-group conflict — one explicit kind per ticket");
    }

    @Test
    void readsOnlyTheScopeRowsInIssueOrder() {
        long first = systemMemoryDraft(projectId, "pending read fact A " + tag);
        long second = systemMemoryDraft(projectId, "pending read fact B " + tag);
        long caseId = seedCase("pending read dialog sibling " + tag);
        minting.caseWriteDraftInMessage(sessionId,
                minting.openAssistantMessage(sessionId), caseId,
                caseService.get(caseId).version(), "{\"title\":\"dialog card\"}");
        systemMemoryDraft(otherProjectId, "pending read foreign " + tag);

        List<PendingGroupCardView> rows =
                confirmationService.pendingGroupCards(userId, sysWire(projectId));

        assertEquals(2, rows.size(),
                "the read is exactly this project's pending system cards of the kind");
        assertEquals(first, rows.get(0).draftId(), "issue order first");
        assertEquals(second, rows.get(1).draftId());
        for (PendingGroupCardView row : rows) {
            assertEquals(projectId, row.projectId());
            assertEquals(KIND_CTX_MEMORY, row.objectType());
            assertEquals(1, row.status(), "every listed row is still pending");
            assertFalse(row.summary().isBlank(), "the card title rides the view");
            assertTrue(row.payloadJson().contains("pending read"),
                    "the proposal body rides the view for card rendering");
        }
    }

    @Test
    void emptyGroupReadsAsEmptyListNotARefusal() {
        List<PendingGroupCardView> rows =
                confirmationService.pendingGroupCards(userId, sysWire(projectId));
        assertTrue(rows.isEmpty(),
                "a panel with nothing pending is a legitimate read, not an error");
    }

    @Test
    void malformedScopeIsRefusedLikeTheIssueDoor() {
        assertThrows(ServiceException.class,
                () -> confirmationService.pendingGroupCards(userId, "nonsense-scope-shape"));
        assertThrows(ServiceException.class,
                () -> confirmationService.pendingGroupCards(userId, null));
    }

    @Test
    void outsiderCannotReadTheProjectGroup() {
        systemMemoryDraft(projectId, "pending read guarded " + tag);
        assertThrows(ServiceException.class,
                () -> confirmationService.pendingGroupCards(outsiderId, sysWire(projectId)),
                "the per-row ownership guard of the issue door applies to the read too");
    }
}
