package com.sixbean.testpike.core.linkage.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.cases.api.dto.CaseUpdateInput;
import com.sixbean.testpike.core.linkage.api.dto.LinkageImpactSummary;
import com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventMessage;
import com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Downstream detection for the case member of the change-linkage bus (
 * segment B segment 2) against the real slot database, no mocks.
 * <p>Consumption semantics under test come straight from the batch design
 * adjudication section 3.1: risk 1 ({@code mark_only}) is a reversible display
 * state the bus writes with zero clicks; risk 3 ({@code disclose_only}) only
 * records a pending disclosure and must never rewrite the issued row; risk 2
 * ({@code grouped_draft}) lands as a draft-pending row that only the one-click
 * human accept can consume. A metadata-only edit must not arm content edges.
 * <p>Fixtures are owned rows only (unique run-suffixed names, deleted by id in
 * teardown, impact rows deleted by upstream case id), and the detection walks
 * the seeded production edge ledger (V514 + the started/unstarted predicate
 * revision) rather than test-local copies, so what passes here is what the bus
 * actually runs in the field. The producer leg (case save → event → consumer)
 * is exercised end-to-end through the real RabbitMQ round trip.
 */
@SpringBootTest(classes = CoreApplication.class)
class CaseLinkageDispatchTest {

    @Autowired
    private ChangeLinkageDispatchService dispatchService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private JdbcTemplate jdbc;

    private long projectId;
    private long caseId;
    private long planCaseId;
    private long batchCaseId;
    private long scriptId;

    @BeforeEach
    void seedCaseWithOneRowPerRiskShape() {
        projectId = jdbc.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, "lnk-case-" + System.nanoTime(), "lnkc" + System.nanoTime());
        CaseInfo created = caseService.create(1L, new CaseCreateInput(
                projectId, "联动基线用例", "前置", "预期", 1, 3, "web", 1, null, 1, null, null));
        caseId = created.id();

        long planId = jdbc.queryForObject(
                "INSERT INTO tp_exec_plan (project_id, name, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, projectId, "lnk-plan-" + System.nanoTime());
        planCaseId = jdbc.queryForObject(
                "INSERT INTO tp_plan_case (plan_id, case_id) VALUES (?, ?) RETURNING id",
                Long.class, planId, caseId);

        long batchId = jdbc.queryForObject(
                "INSERT INTO tp_exec_batch (project_id, status) VALUES (?, 1) RETURNING id",
                Long.class, projectId);
        batchCaseId = jdbc.queryForObject(
                "INSERT INTO tp_batch_case (batch_id, case_id) VALUES (?, ?) RETURNING id",
                Long.class, batchId, caseId);
        jdbc.update("UPDATE tp_exec_batch SET status = 4 WHERE id = ?", batchId);

        scriptId = jdbc.queryForObject(
                "INSERT INTO tp_script (case_id, status) VALUES (?, 1) RETURNING id",
                Long.class, caseId);
    }

    @AfterEach
    void removeOwnedRows() {
        jdbc.update("DELETE FROM tp_linkage_impact WHERE upstream_object_type = 'case'"
                + " AND upstream_object_id = ?", caseId);
        jdbc.update("DELETE FROM tp_script WHERE id = ?", scriptId);
        jdbc.update("DELETE FROM tp_batch_case WHERE id = ?", batchCaseId);
        jdbc.update("DELETE FROM tp_exec_batch b WHERE NOT EXISTS"
                + " (SELECT 1 FROM tp_batch_case bc WHERE bc.batch_id = b.id)"
                + " AND b.project_id = ?", projectId);
        jdbc.update("DELETE FROM tp_plan_case WHERE id = ?", planCaseId);
        jdbc.update("DELETE FROM tp_exec_plan p WHERE NOT EXISTS"
                + " (SELECT 1 FROM tp_plan_case pc WHERE pc.plan_id = p.id)"
                + " AND p.project_id = ?", projectId);
        jdbc.update("DELETE FROM tp_case_step WHERE case_id = ?", caseId);
        jdbc.update("DELETE FROM tp_test_case WHERE id = ?", caseId);
        jdbc.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private ObjectChangedEventMessage caseEvent(List<String> changedFields, int version) {
        return new ObjectChangedEventMessage(ObjectChangedEventType.CASE.name(), caseId,
                projectId, 1L, version, changedFields, System.currentTimeMillis());
    }

    private String ledgerStrategy(String edgeKey, long downstreamId) {
        return jdbc.queryForObject(
                "SELECT disposition_strategy FROM tp_linkage_impact"
                        + " WHERE edge_key = ? AND downstream_id = ?",
                String.class, edgeKey, downstreamId);
    }

    private int ledgerStatus(String edgeKey, long downstreamId) {
        return jdbc.queryForObject(
                "SELECT status FROM tp_linkage_impact WHERE edge_key = ? AND downstream_id = ?",
                Integer.class, edgeKey, downstreamId);
    }

    private int ledgerCount(String edgeKey, long downstreamId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM tp_linkage_impact WHERE edge_key = ? AND downstream_id = ?",
                Integer.class, edgeKey, downstreamId);
    }

    @Test
    void unstarted_plan_reference_gets_the_zero_click_stale_mark() {
        LinkageImpactSummary summary = dispatchService.detectDownstream(
                caseEvent(List.of("title"), 2));

        assertNotNull(summary);
        assertEquals("mark_only", ledgerStrategy("case->plan_case", planCaseId));
        assertEquals(1, ledgerStatus("case->plan_case", planCaseId));
    }

    @Test
    void issued_batch_reference_is_only_disclosed_never_rewritten() {
        dispatchService.detectDownstream(caseEvent(List.of("title"), 2));

        assertEquals("disclose_only", ledgerStrategy("case->batch_case", batchCaseId));
        assertEquals(3, ledgerStatus("case->batch_case", batchCaseId));
        // the ledger is the disclosure surface: the issued batch row itself stays untouched
        assertEquals(4, jdbc.queryForObject(
                "SELECT b.status FROM tp_exec_batch b JOIN tp_batch_case bc ON bc.batch_id = b.id"
                        + " WHERE bc.id = ?", Integer.class, batchCaseId));
    }

    @Test
    void content_edit_arms_the_script_draft_edge_but_priority_touch_does_not() {
        LinkageImpactSummary meta = dispatchService.detectDownstream(
                caseEvent(List.of("priority"), 2));
        assertEquals(0, ledgerCount("case->script", scriptId),
                "a metadata-only save must not arm the content edge");
        assertTrue(meta.pendingDrafts().isEmpty(),
                "an un-armed content edge must not request a repair proposal");

        LinkageImpactSummary armed = dispatchService.detectDownstream(
                caseEvent(List.of("title"), 3));
        assertEquals("grouped_draft", ledgerStrategy("case->script", scriptId));
        assertEquals(2, ledgerStatus("case->script", scriptId),
                "content edges wait for the one-click human accept, never auto-apply");
        assertTrue(armed.pendingDrafts().contains("script:" + scriptId),
                "the armed script edge must surface as a pending draft for the repair chain");
    }

    @Test
    void replaying_the_announcement_requests_no_second_proposal() {
        dispatchService.detectDownstream(caseEvent(List.of("title"), 2));
        LinkageImpactSummary again = dispatchService.detectDownstream(
                caseEvent(List.of("title"), 2));

        assertTrue(again.pendingDrafts().isEmpty(),
                "a replayed version changes no ledger row and asks for no draft");
    }

    @Test
    void replaying_the_same_announcement_stays_idempotent_in_the_ledger() {
        dispatchService.detectDownstream(caseEvent(List.of("title"), 2));
        LinkageImpactSummary again = dispatchService.detectDownstream(
                caseEvent(List.of("title"), 2));

        assertEquals(1, ledgerCount("case->plan_case", planCaseId));
        assertEquals(0, again.impactsCreated() + again.impactsUpdated(),
                "a replayed version must change no ledger row");
    }

    @Test
    void case_save_end_to_end_lands_the_stale_mark_through_the_real_bus() {
        CaseInfo updated = caseService.update(1L, caseId,
                new CaseUpdateInput("联动基线用例-改名", null, null, null, null, null, null, null),
                1);
        assertEquals(2, updated.version());

        long deadline = System.currentTimeMillis() + 15_000;
        int rows = 0;
        while (System.currentTimeMillis() < deadline) {
            rows = ledgerCount("case->plan_case", planCaseId);
            if (rows > 0) {
                break;
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(rows > 0, "the producer-consumer leg must persist the stale mark");
        assertEquals("mark_only", ledgerStrategy("case->plan_case", planCaseId));
    }
}
