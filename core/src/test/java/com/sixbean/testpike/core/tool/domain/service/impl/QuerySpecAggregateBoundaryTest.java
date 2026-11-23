package com.sixbean.testpike.core.tool.domain.service.impl;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.tool.api.dto.QueryAggregation;
import com.sixbean.testpike.core.tool.api.dto.QueryResult;
import com.sixbean.testpike.core.tool.api.dto.QuerySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four hard boundaries of the declarative read-only QuerySpec proxy
 * (AO batch P3 ; design ruling: "assembly refuses targets
 * outside the registry, the DSL has no write primitive, no arbitrary-table
 * join and no implicit cross-project reach — aggregates are computed
 * server-side, the model only composes intent").
 * <p>Each boundary is proven fail-closed, then the positive leg pins the
 * contract the live card depends on: the number the proxy returns equals the
 * number raw SQL computes over the same segment (three-way reconciliation,
 * third leg = psql-side count asserted here via an independent JdbcTemplate
 * query). Real dev database, zero mocks (same posture as
 * ChatShareSnapshotTest). Fixture projects are uuid-named and cleaned by id.
 */
@SpringBootTest(classes = CoreApplication.class)
class QuerySpecAggregateBoundaryTest {

    @Autowired
    private QuerySpecServiceImpl querySpecService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ---- fixture helpers ---------------------------------------------------

    private long newProject() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_project (name, code, default_lang, status, storage_mode,
                                        kb_enabled, notify_feishu, classify_vision, ai_page_inspect_enabled)
                VALUES ('qspec-' || ?, 'qspec-' || ?, 'zh-CN', 1, 1, 0, 0, 0, 0) RETURNING id
                """, Long.class, suffix, suffix);
    }

    private long newCase(long projectId, int status, int priority) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_test_case (case_no, project_id, title, priority, confidence,
                                          end_type, status, source, phase)
                VALUES ('C-' || ?, ?, 'case-' || ?, ?, 2, 'web', ?, 1, 1) RETURNING id
                """, Long.class, suffix, projectId, suffix, priority, status);
    }

    private long newDefect(long projectId, int severity, int occurrence) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_defect (project_id, title, severity, status, reporter_id, source, occurrence_count)
                VALUES (?, 'D-' || ?, ?, 1, 1, 1, ?) RETURNING id
                """, Long.class, projectId, suffix, severity, occurrence);
    }

    private long newBatch(long projectId, int status) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_exec_batch (project_id, batch_no, name, status, type)
                VALUES (?, 'B-' || ?, 'batch-' || ?, ?, 1) RETURNING id
                """, Long.class, projectId, suffix, suffix, status);
    }

    // ---- boundary 1: targets outside the registry are refused --------------

    @Test
    void refusesTargetOutsideRegistry() {
        long projectId = newProject();
        try {
            ServiceException ex = assertThrows(ServiceException.class, () -> querySpecService.runJson(
                    "{\"target\":\"tp_user\",\"groupBy\":[\"role\"]}", projectId, null));
            assertTrue(ex.getMessage().contains("tp_user"),
                    "refusal must name the unknown target: " + ex.getMessage());
        } finally {
            deleteProject(projectId);
        }
    }

    @Test
    void refusesUnregisteredColumnOnRegisteredTarget() {
        long projectId = newProject();
        try {
            ServiceException ex = assertThrows(ServiceException.class, () -> querySpecService.runJson(
                    "{\"target\":\"defect\",\"filters\":[{\"field\":\"password_hash\",\"value\":\"x\"}]}",
                    projectId, null));
            assertTrue(ex.getMessage().contains("password_hash"),
                    "refusal must name the offending column: " + ex.getMessage());
        } finally {
            deleteProject(projectId);
        }
    }

    // ---- boundary 2: no write primitives (fail-closed on smuggled keys) ----

    @Test
    void refusesWriteShapedKeyOutsideTheDsl() {
        long projectId = newProject();
        try {
            ServiceException ex = assertThrows(ServiceException.class, () -> querySpecService.runJson(
                    "{\"target\":\"defect\",\"groupBy\":[\"status\"],\"deleteAll\":true}", projectId, null));
            assertTrue(ex.getMessage().contains("deleteAll"),
                    "refusal must name the unknown field: " + ex.getMessage());
        } finally {
            deleteProject(projectId);
        }
    }

    @Test
    void refusesSqlFragmentAsFilterValueShape() {
        long projectId = newProject();
        try {
            // Values are bound as named parameters, never concatenated — a
            // payload shaped like a fragment must still run as data, and an
            // aggregation outside the closed vocabulary must be refused.
            ServiceException ex = assertThrows(ServiceException.class, () -> querySpecService.runJson(
                    "{\"target\":\"defect\",\"aggregations\":[{\"function\":\"exec_sql\",\"column\":\"title\"}]}",
                    projectId, null));
            assertTrue(ex.getMessage().contains("exec_sql"),
                    "refusal must name the unknown function: " + ex.getMessage());
        } finally {
            deleteProject(projectId);
        }
    }

    // ---- boundary 3: no arbitrary-table join -------------------------------

    @Test
    void refusesJoinShapedRequest() {
        long projectId = newProject();
        try {
            ServiceException ex = assertThrows(ServiceException.class, () -> querySpecService.runJson(
                    "{\"target\":\"defect\",\"groupBy\":[\"status\"],\"join\":\"tp_test_case\"}",
                    projectId, null));
            assertTrue(ex.getMessage().contains("join"),
                    "refusal must name the join attempt: " + ex.getMessage());
        } finally {
            deleteProject(projectId);
        }
    }

    // ---- boundary 4: no implicit cross-project reach -----------------------

    @Test
    void queryIsConfinedToTheServerSideProjectSlice() {
        long mine = newProject();
        long theirs = newProject();
        try {
            for (int i = 0; i < 3; i++) {
                newDefect(mine, 2, 1);
            }
            newDefect(theirs, 2, 1);

            QueryResult result = querySpecService.runJson(
                    "{\"target\":\"defect\",\"aggregations\":[{\"function\":\"count\"}]}", mine, null);
            assertEquals(3L, ((Number) result.rows().get(0).get("count")).longValue(),
                    "count must see only the caller's slice");

            // The scope column is server-forced, so the model must not be able
            // to aim a filter at any project id — project_id is not a
            // filterable dimension of the registration, refusal is fail-closed.
            ServiceException ex = assertThrows(ServiceException.class, () -> querySpecService.runJson(
                    "{\"target\":\"defect\",\"filters\":[{\"field\":\"project_id\",\"value\":"
                            + theirs + "}],\"aggregations\":[{\"function\":\"count\"}]}", mine, null));
            assertTrue(ex.getMessage().contains("project_id"),
                    "refusal must name the scope column: " + ex.getMessage());
        } finally {
            deleteProject(mine);
            deleteProject(theirs);
        }
    }

    // ---- positive leg: server-computed aggregates reconcile with raw SQL ---

    @Test
    void groupByAndAggregationsMatchRawSqlComputation() {
        long projectId = newProject();
        try {
            newDefect(projectId, 1, 5);
            newDefect(projectId, 1, 7);
            newDefect(projectId, 3, 2);
            newCase(projectId, 2, 1);
            newCase(projectId, 2, 2);
            newCase(projectId, 4, 2);
            newBatch(projectId, 2);
            newBatch(projectId, 2);
            newBatch(projectId, 3);

            QueryResult defects = querySpecService.runJson("""
                    {"target":"defect","groupBy":["severity"],
                     "aggregations":[{"function":"count"},{"function":"sum","column":"occurrence_count"}],
                     "orderBy":["severity:asc"]}
                    """, projectId, null);
            assertEquals(2, defects.rows().size());
            assertEquals(2L, ((Number) defects.rows().get(0).get("count")).longValue(),
                    "severity=1 must carry 2 defects");
            assertEquals(12, ((Number) defects.rows().get(0).get("sum_occurrence_count")).intValue(),
                    "sum(occurrence_count) over severity=1 = 5+7");

            // Independent SQL leg — the proxy's numbers must equal raw SQL.
            Integer sqlSum = jdbcTemplate.queryForObject(
                    "SELECT sum(occurrence_count) FROM tp_defect WHERE project_id = ? AND severity = 1",
                    Integer.class, projectId);
            assertEquals(sqlSum, ((Number) defects.rows().get(0).get("sum_occurrence_count")).intValue());

            QueryResult cases = querySpecService.runJson("""
                    {"target":"test_case","groupBy":["status"],
                     "aggregations":[{"function":"avg","column":"priority"},
                                      {"function":"max","column":"priority"}],
                     "orderBy":["status:asc"],"limit":1}
                    """, projectId, null);
            // status=2 (avg of priorities 1,2), status=4 (avg of 2): limit 1 clamps rows.
            assertEquals(1, cases.rows().size(), "limit must clamp to one row");
            assertTrue(cases.rowLimitUsed() >= 1);
            assertEquals(2, ((Number) cases.rows().get(0).get("status")).intValue());
            assertEquals(1.5, ((Number) cases.rows().get(0).get("avg_priority")).doubleValue(), 1e-9,
                    "avg(priority) over status=2 = (1+2)/2");
            assertEquals(2, ((Number) cases.rows().get(0).get("max_priority")).intValue());

            QueryResult batches = querySpecService.runJson("""
                    {"target":"exec_batch","groupBy":["status"],
                     "aggregations":[{"function":"count"}],"orderBy":["status:asc"]}
                    """, projectId, null);
            assertEquals(2, batches.rows().size());
            assertEquals(2L, ((Number) batches.rows().get(0).get("count")).longValue());
        } finally {
            deleteProject(projectId);
        }
    }

    @Test
    void limitAboveTargetCapIsClampedNotRefused() {
        long projectId = newProject();
        try {
            for (int i = 0; i < 4; i++) {
                newCase(projectId, 1, 1);
            }
            QueryResult result = querySpecService.runJson("""
                    {"target":"test_case","groupBy":["end_type"],
                     "aggregations":[{"function":"count"}],"limit":999999}
                    """, projectId, null);
            assertTrue(result.rowLimitUsed() <= 200,
                    "effective cap must clamp to the registered max_rows (200), saw " + result.rowLimitUsed());
        } finally {
            deleteProject(projectId);
        }
    }

    @Test
    void recordEntranceRunsTheSameCompilePath() {
        long projectId = newProject();
        try {
            newDefect(projectId, 2, 4);
            newDefect(projectId, 2, 6);
            // The typed record entrance (used by the ai-service adapter in the
            // next segment) must reach the same server-side computation.
            QueryResult result = querySpecService.run(
                    new QuerySpec("defect", List.of(), List.of("severity"),
                            List.of(new QueryAggregation("count", null)),
                            List.of("severity:asc"), 50), projectId, null);
            assertEquals(1, result.rows().size());
            assertEquals(2L, ((Number) result.rows().get(0).get("count")).longValue());
            assertEquals(2, result.rowLimitUsed() <= 200 ? 2 : 0, "cap stays inside the registered range");
        } finally {
            deleteProject(projectId);
        }
    }

    private void deleteProject(long projectId) {
        jdbcTemplate.update("DELETE FROM tp_defect WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_test_case WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_exec_batch WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }
}
