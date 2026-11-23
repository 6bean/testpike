package com.sixbean.testpike.core.webelement.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.executor.api.dto.ExecResultInput;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskInfo;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Direct-heal evidence writeback (03 protocol 2.4 trailing key
 * stepHealEvidence). The executor reports one row per healed step (which
 * element healed, which anchor failed, which anchor won, at what visual
 * similarity and ledger revision); this component is the single place that
 * lands those rows into tp_exec_step_heal_evidence (V554).
 * <p>Contracts nailed here:
 * ① a full relay persists every row with nested anchor halves mapped into
 *   the flat from_/used_ type+value columns;
 * ② absent trailing key and non-browser task types write zero rows (legacy
 *   relays and api/app tasks keep their exact shape, zero drift);
 * ③ an un-parseable evidence plane is logged and swallowed — the result
 *   relay chain must never break because of the evidence hook;
 * ④ a row without an element id still lands (element_id is deliberately
 *   FK-free: a ledger element retired after the run must not destroy the
 *   historical evidence);
 * ⑤ R57 bound: deleting the task drops its evidence rows through the
 *   ON DELETE CASCADE FK.
 * <p>Real chain: the project is created through the live service against
 * the chain-slot DB; the task row is inserted directly (the hook only needs
 * an existing tp_exec_task id) and every fixture is purged by project/task
 * scope in teardown.
 */
@SpringBootTest(classes = CoreApplication.class)
class StepHealEvidenceWritebackTest {

    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    private StepHealEvidenceWriteback writeback;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    private long projectId;
    private long taskId;

    @BeforeEach
    void seedProject() {
        long tag = SEQ.incrementAndGet();
        String code = "SH" + System.currentTimeMillis() % 100000000L + tag;
        projectId = projectService.create(1L, new ProjectCreateInput(
                "自愈证据回写项目" + code, code, "zh-CN", "P1", null));
        taskId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_exec_task (project_id, case_id, status, task_type) "
                        + "VALUES (?, ?, 3, 1) RETURNING id",
                Long.class, projectId, 900000L + tag);
    }

    @AfterEach
    void removeFixtures() {
        // Evidence rows follow the task via the V554 FK cascade — the task
        // delete below is the single cleanup anchor (R57 terminal deletion).
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE id = ?", taskId);
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private ExecTaskInfo task(int taskType) {
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("id", taskId);
        shape.put("projectId", projectId);
        shape.put("taskType", taskType);
        return objectMapper.convertValue(shape, ExecTaskInfo.class);
    }

    private ExecResultInput input(Object evidence) {
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("taskId", taskId);
        shape.put("result", "pass");
        if (evidence != null) {
            shape.put("stepHealEvidence", evidence);
        }
        return objectMapper.convertValue(shape, ExecResultInput.class);
    }

    private static Map<String, Object> evidenceRow(Long elementId, int stepNo,
                                                   Double similarity) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("healed", true);
        row.put("firstAnchorFailed", true);
        row.put("elementId", elementId);
        row.put("stepNo", stepNo);
        row.put("fromAnchor", Map.of("type", "css", "value", "#old"));
        row.put("usedAnchor", Map.of("type", "testid", "value", "submit"));
        row.put("viewport", "1440x900");
        row.put("ledgerRevision", 7);
        row.put("similarityScore", similarity);
        return row;
    }

    private List<Map<String, Object>> storedRows() {
        return jdbcTemplate.queryForList(
                "SELECT * FROM tp_exec_step_heal_evidence WHERE task_id = ? ORDER BY id",
                taskId);
    }

    @Test
    void fullRelayPersistsEveryRowWithFlatAnchorColumns() {
        writeback.onResultSubmitted(task(1), input(
                List.of(evidenceRow(11L, 2, 0.91), evidenceRow(12L, 3, null))));
        List<Map<String, Object>> rows = storedRows();
        assertEquals(2, rows.size());
        Map<String, Object> first = rows.get(0);
        assertEquals(11L, first.get("element_id"));
        assertEquals(2, first.get("step_no"));
        assertEquals(Boolean.TRUE, first.get("healed"));
        assertEquals(Boolean.TRUE, first.get("first_anchor_failed"));
        assertEquals("css", first.get("from_anchor_type"));
        assertEquals("#old", first.get("from_anchor_value"));
        assertEquals("testid", first.get("used_anchor_type"));
        assertEquals("submit", first.get("used_anchor_value"));
        assertEquals("1440x900", first.get("viewport"));
        assertEquals(7, first.get("ledger_revision"));
        assertEquals(0.91, ((Number) first.get("similarity_score")).doubleValue(), 1e-9);
        assertEquals(null, rows.get(1).get("similarity_score"));
    }

    @Test
    void absentTrailingKeyAndNonBrowserTasksWriteZeroRows() {
        writeback.onResultSubmitted(task(1), input(null));
        writeback.onResultSubmitted(task(4), input(List.of(evidenceRow(21L, 1, 0.8))));
        assertEquals(0, storedRows().size());
    }

    @Test
    void malformedEvidencePlaneIsSwallowedAndKeepsRelayAlive() {
        // A list of plain strings cannot map onto the record: the writeback
        // drops the evidence plane but never rethrows into submitResult.
        assertDoesNotThrow(() -> writeback.onResultSubmitted(
                task(1), input(List.of("not-an-evidence-row"))));
        assertEquals(0, storedRows().size());
    }

    @Test
    void rowWithoutElementIdStillLandsAsHistoricalEvidence() {
        writeback.onResultSubmitted(task(1), input(
                List.of(evidenceRow(null, 4, 0.77))));
        List<Map<String, Object>> rows = storedRows();
        assertEquals(1, rows.size());
        assertEquals(null, rows.get(0).get("element_id"));
        assertEquals(4, rows.get(0).get("step_no"));
    }

    @Test
    void taskDeletionCascadesEvidenceRows() {
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            many.add(evidenceRow(30L + i, i, 0.9));
        }
        writeback.onResultSubmitted(task(1), input(many));
        assertEquals(5, storedRows().size());
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE id = ?", taskId);
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_exec_step_heal_evidence WHERE task_id = ?",
                Integer.class, taskId));
    }
}
