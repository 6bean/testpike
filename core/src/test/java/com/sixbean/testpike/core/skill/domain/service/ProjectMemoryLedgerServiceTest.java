package com.sixbean.testpike.core.skill.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.skill.api.dto.ProjectMemoryCreateInput;
import com.sixbean.testpike.core.skill.api.dto.ProjectMemoryInfo;
import com.sixbean.testpike.core.skill.api.dto.SkillAssemblyItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Project-memory ledger service (AP batch , core ledger + state machine
 * + assembly single-source exclusion). Real dev DB, no mock, no TRUNCATE of factory
 * tables (R37): every fixture project/entry is created here and cleaned by id.
 * <p>Covered contracts (ruling doc section 7 a)-c) + final ruling three-state):
 * ① list = active rows of the owning project only, keyword/status filters, pagination;
 * ② manual create = fixed injection entry with source_ref [{type:manual}], same
 * assembly channel as distilled rows (no second injection chain, R40);
 * ③ disable hides the entry from the NEXT assembly read, re-enable brings it back;
 * ④ delete moves the entry to the recycle bin (never erased) and excludes it from
 * assembly; restore returns it with its original inject_status;
 * ⑤ disable/delete/restore of a missing or foreign-project id fail with 40401.
 */
@SpringBootTest(classes = CoreApplication.class)
class ProjectMemoryLedgerServiceTest {

    @Autowired
    private ProjectMemoryLedgerService ledgerService;

    @Autowired
    private SkillConsumptionService skillConsumptionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private final List<Long> createdMemoryIds = new java.util.ArrayList<>();

    @BeforeEach
    void createProject() {
        String code = "tp449-" + UUID.randomUUID().toString().substring(0, 8);
        projectId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_project (name, code, default_lang, created_by)
                VALUES (?, ?, 'zh-CN', 1) RETURNING id
                """, Long.class, "项目记忆治理" + code, code);
    }

    @AfterEach
    void cleanUp() {
        if (!createdMemoryIds.isEmpty()) {
            String ids = createdMemoryIds.stream().map(String::valueOf).collect(Collectors.joining(","));
            jdbcTemplate.update("DELETE FROM tp_ai_ctx_memory WHERE id IN (" + ids + ")");
        }
// fixture audit rows stay: mid-range ledger deletion forges chain breaks
        jdbcTemplate.update("DELETE FROM tp_ai_ctx_memory WHERE project_id = ?", projectId);
        // Draft anchors created by the spectra ride the same fixture project.
        jdbcTemplate.update("DELETE FROM tp_ai_chat_draft WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    // ---- helpers ----

    private long insertEntry(String content, int injectStatus, String sourceRef) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO tp_ai_ctx_memory (project_id, content, source_ref, inject_status)
                VALUES (?, ?, CAST(? AS JSONB), ?) RETURNING id
                """, Long.class, projectId, content, sourceRef, injectStatus);
        createdMemoryIds.add(id);
        return id;
    }

    /**
 * File a draft ledger row anchored to one memory entry the
 * awaiting-confirmation row must expose its open card so the panel can
 * route the operator to the shared confirmation door).
 */
    private long insertDraft(long targetMemoryId, int status) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO tp_ai_chat_draft
                    (project_id, object_type, target_id, summary, payload_json, status)
                VALUES (?, 'ctx_memory', ?, '草案锚夹具', '{}', ?) RETURNING id
                """, Long.class, projectId, targetMemoryId, status);
        return id;
    }

    private Set<Long> assembledMemoryIds() {
        return skillConsumptionService.getAssemblyItems(projectId).stream()
                .filter(i -> i.kind() == 1)
                .map(SkillAssemblyItem::skillId)
                .collect(Collectors.toSet());
    }

    // ---- ① list ----

    @Test
    void listReturnsActiveRowsOfOwningProjectWithFiltersAndPaging() {
        insertEntry("接口超时统一 30 秒", 1, "[{\"type\":\"rejection\",\"id\":11}]");
        insertEntry("金额字段保留两位小数", 0, "[{\"type\":\"downvote\",\"id\":22}]");
        long foreign = jdbcTemplate.queryForObject("""
                INSERT INTO tp_project (name, code, default_lang, created_by)
                VALUES ('foreign-tp449', ?, 'zh-CN', 1) RETURNING id
                """, Long.class, "tp449-f-" + UUID.randomUUID().toString().substring(0, 8));
        try {
            Long foreignEntry = jdbcTemplate.queryForObject("""
                    INSERT INTO tp_ai_ctx_memory (project_id, content, source_ref, inject_status)
                    VALUES (?, '别项目的约定', '[]', 1) RETURNING id
                    """, Long.class, foreign);
            createdMemoryIds.add(foreignEntry);

            PageResult<ProjectMemoryInfo> all = ledgerService.list(projectId, null, null, 1, 20);
            assertEquals(2, all.total(), "list must show active+disabled rows of this project only");

            PageResult<ProjectMemoryInfo> enabled = ledgerService.list(projectId, null, 1, 1, 20);
            assertEquals(1, enabled.total(), "status filter 1 keeps only eligible-for-injection rows");

            PageResult<ProjectMemoryInfo> byKeyword = ledgerService.list(projectId, "金额", null, 1, 20);
            assertEquals(1, byKeyword.total(), "keyword must match entry content");
            assertEquals(0, byKeyword.list().get(0).injectStatus());

            ProjectMemoryInfo row = byKeyword.list().get(0);
            assertNotNull(row.sourceRef(), "ledger row must carry traceability anchors");
            assertTrue(row.sourceRef().contains("downvote"));

            PageResult<ProjectMemoryInfo> paged = ledgerService.list(projectId, null, null, 2, 1);
            assertEquals(2, paged.total());
            assertEquals(1, paged.list().size(), "page 2 size 1 must not leak page 1 rows");
        } finally {
            jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", foreign);
        }
    }

    // ---- ② manual create = fixed injection, same assembly channel ----

    @Test
    void manualCreateBecomesFixedInjectionEntryOnTheSameAssemblyChannel() {
        ProjectMemoryInfo created = ledgerService.create(1L,
                new ProjectMemoryCreateInput(projectId, "回归用例命名保持中文动宾结构"));
        createdMemoryIds.add(created.id());

        assertEquals(1, created.injectStatus(), "manual entry leads as eligible for injection");
        assertTrue(created.sourceRef().contains("manual"), "manual origin must be traceable");
        assertTrue(assembledMemoryIds().contains(created.id()),
                "manual entry must ride the existing assembly read point (no second chain)");

        assertThrows(ServiceException.class,
                () -> ledgerService.create(1L, new ProjectMemoryCreateInput(projectId, "  ")));
        assertThrows(ServiceException.class,
                () -> ledgerService.create(1L, new ProjectMemoryCreateInput(999999999L, "存在的项目之外")));
    }

    // ---- ③ disable/enable <-> assembly exclusion live proof ----

    @Test
    void disableHidesEntryFromNextAssemblyUntilReEnabled() {
        long id = insertEntry("分页默认 20 条", 1, "[]");
        assertTrue(assembledMemoryIds().contains(id), "precondition: enabled entry is assembled");

        ledgerService.setInjectStatus(1L, projectId, id, 0);
        assertFalse(assembledMemoryIds().contains(id),
                "disabled entry must vanish from the NEXT assembly read");

        ledgerService.setInjectStatus(1L, projectId, id, 1);
        assertTrue(assembledMemoryIds().contains(id), "re-enabled entry is assembled again");

        assertThrows(ServiceException.class, () -> ledgerService.setInjectStatus(1L, projectId, id, 5));
    }

    // ---- ④ delete -> recycle bin -> restore, assembly single source ----

    @Test
    void deleteMovesToRecycleBinAndRestoreBringsEntryBackWithOriginalStatus() {
        long keep = insertEntry("删除进回收站不物理抹除", 1, "[{\"type\":\"healing_diff\",\"id\":33}]");
        long disabled = insertEntry("停用态被删除的条目", 0, "[]");

        ledgerService.delete(1L, projectId, keep);

        PageResult<ProjectMemoryInfo> bin = ledgerService.recycleBin(projectId, 1, 20);
        assertEquals(1, bin.total(), "recycle bin lists exactly the soft-deleted rows");
        assertEquals(keep, bin.list().get(0).id());
        assertFalse(assembledMemoryIds().contains(keep),
                "deleted entry must not be assembled (recycle bin, not erased)");

        ledgerService.restore(1L, projectId, keep);
        assertTrue(assembledMemoryIds().contains(keep), "restored entry rejoins assembly");
        assertEquals(0, ledgerService.recycleBin(projectId, 1, 20).total());

        ledgerService.delete(1L, projectId, disabled);
        ledgerService.restore(1L, projectId, disabled);
        assertFalse(assembledMemoryIds().contains(disabled),
                "restore must keep the original disabled state, not re-enable silently");
    }

    // ---- ⑤ cross-project / missing id rejected ----

    @Test
    void foreignOrMissingEntryIdsAreRejectedOnEveryGovernanceAction() {
        long id = insertEntry("越权治理必须撞墙", 1, "[]");
        assertThrows(ServiceException.class, () -> ledgerService.setInjectStatus(1L, id + 999999, id, 0));
        assertThrows(ServiceException.class, () -> ledgerService.delete(1L, id + 999999, id));
        assertThrows(ServiceException.class, () -> ledgerService.restore(1L, projectId, id));
        assertTrue(assembledMemoryIds().contains(id), "rejected actions must not change the entry");
    }

    // ---- ⑥ four-state gate : 2 pending / 1 active / 0 disabled / 3 rejected ----

    @Test
    void pendingEntryStaysOutOfAssemblyUntilTheConfirmationDoorPromotesIt() {
        long id = insertEntry("待确认的蒸馏约定", 2, "[{\"type\":\"distill\"}]");
        assertFalse(assembledMemoryIds().contains(id),
                "a distilled entry awaiting confirmation must never be injected (re-confirmed ruling, plan A)");

        // The door's promotion side effect (CtxMemoryDraftStrategy flips 2→1 in
        // its own transaction; the S2b spectrum proves the door path end to
        // end). Here the flip is replayed to assert the assembly consequence.
        jdbcTemplate.update("UPDATE tp_ai_ctx_memory SET inject_status = 1 WHERE id = ?", id);
        assertTrue(assembledMemoryIds().contains(id),
                "a confirmed entry becomes injectable through the same single read point");
    }

    @Test
    void ledgerCannotSelfConfirmAPendingEntryThroughTheStatusRoute() {
        long id = insertEntry("不能绕过确认门直启", 2, "[]");
        assertThrows(ServiceException.class, () -> ledgerService.setInjectStatus(1L, projectId, id, 1),
                "flipping a pending row to active without a ticket would bypass the confirmation door");
        assertThrows(ServiceException.class, () -> ledgerService.setInjectStatus(1L, projectId, id, 0),
                "a pending row must be confirmed or rejected, not silently disabled");
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT inject_status FROM tp_ai_ctx_memory WHERE id = ?", Integer.class, id),
                "refused actions must leave the state untouched");
    }

    @Test
    void rejectTurnsPendingEntryToRejectedWithAuditAndIsIdempotent() {
        long id = insertEntry("被驳回的蒸馏条目", 2, "[{\"type\":\"distill\"}]");
        ProjectMemoryInfo after = ledgerService.reject(1L, projectId, id);
        assertEquals(3, after.injectStatus(), "rejection flips pending (2) to rejected (3)");
        assertFalse(assembledMemoryIds().contains(id), "a rejected entry is never injected");

        // Repeated rejection succeeds without stacking a second audit row.
        assertEquals(3, ledgerService.reject(1L, projectId, id).injectStatus());
        Long auditRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_audit_log WHERE object_type = 'project_memory' AND object_id = ? "
                        + "AND action = 'reject'", Long.class, String.valueOf(id));
        assertEquals(1L, auditRows, "idempotent rejection must not stack audit rows");
    }

    @Test
    void rejectRefusesActiveDisabledMissingAndForeignRowsWithZeroWrites() {
        long active = insertEntry("生效条目不可驳回", 1, "[]");
        long disabled = insertEntry("停用条目不可驳回", 0, "[]");
        assertThrows(ServiceException.class, () -> ledgerService.reject(1L, projectId, active));
        assertThrows(ServiceException.class, () -> ledgerService.reject(1L, projectId, disabled));
        assertThrows(ServiceException.class, () -> ledgerService.reject(1L, projectId, active + 999999));
        assertThrows(ServiceException.class, () -> ledgerService.reject(1L, active + 999999, active));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT inject_status FROM tp_ai_ctx_memory WHERE id = ?", Integer.class, active),
                "refused rejections must not touch the row");
    }

    // ---- ⑦ draft anchor exposure ----

    @Test
    void pendingRowExposesItsOpenDraftAnchorAndTheNewestCardWins() {
        long pendingId = insertEntry("带草案锚的待确认条目", 2, "[{\"type\":\"distill\"}]");
        insertDraft(pendingId, 1);
        long newest = insertDraft(pendingId, 1);

        ProjectMemoryInfo row = ledgerService.list(projectId, null, 2, 1, 20).list().get(0);
        assertNotNull(row.draftId(), "the awaiting row must expose its open draft card");
        assertEquals(newest, row.draftId().longValue(),
                "with several open cards the newest one anchors the panel route");
    }

    @Test
    void theAnchorFollowsOpenCardsNotRowStatesAndAppliedHistoryStaysSilent() {
        long activeId = insertEntry("生效条目没有可确认卡", 1, "[]");
        long rejectedId = insertEntry("驳回条目没有可确认卡", 3, "[]");
        long appliedOnlyId = insertEntry("仅已应用草案不算开卡", 2, "[{\"type\":\"distill\"}]");
        insertDraft(appliedOnlyId, 2); // applied (2) drafts are history, not an open card

        assertNull(ledgerService.list(projectId, null, 1, 1, 20).list().get(0).draftId(),
                "a row without any card shows no anchor");
        assertNull(ledgerService.list(projectId, null, 3, 1, 20).list().get(0).draftId(),
                "a rejected row filed without a card shows no anchor");
        assertNull(ledgerService.list(projectId, null, 2, 1, 20).list().get(0).draftId(),
                "applied-draft history must not masquerade as an open card");

        // The anchor is a property of the open card pointing at the row, not
        // of the row's state — a card left open on an already-active row is
        // still surfaced for traceability (the door itself refuses it loudly,
        // per CtxMemoryDraftStrategy).
        long open = insertDraft(activeId, 1);
        ProjectMemoryInfo touched = ledgerService.list(projectId, null, 1, 1, 20).list().get(0);
        assertEquals(open, touched.draftId().longValue());
        assertTrue(rejectedId > 0);
    }

    @Test
    void rejectedEntryStillListsAndFiltersByState() {
        insertEntry("驳回后仍在账本可查", 3, "[{\"type\":\"distill\"}]");
        insertEntry("待确认可筛", 2, "[]");
        PageResult<ProjectMemoryInfo> rejected = ledgerService.list(projectId, null, 3, 1, 20);
        assertEquals(1, rejected.total(), "state filter 3 shows the rejected ledger row");
        PageResult<ProjectMemoryInfo> pending = ledgerService.list(projectId, null, 2, 1, 20);
        assertEquals(1, pending.total(), "state filter 2 shows the awaiting-confirmation row");
        PageResult<ProjectMemoryInfo> all = ledgerService.list(projectId, null, null, 1, 20);
        assertEquals(2, all.total(), "the ledger keeps every non-deleted state visible");
    }
}
