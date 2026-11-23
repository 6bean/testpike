package com.sixbean.testpike.core.mock.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Declarative mock binding surface for cases and regression suites .
 * <p>Two claims verified here:
 * (1) schema — tp_test_case and tp_regression_suite each carry a nullable mock_space_id
 * column (NULL = run against the real environment; the executor only rewrites base_url /
 * installs route interception when a published space is named);
 * (2) the single reference gate — MockSpaceReferenceGate.blockingReferences must count live
 * case and suite bindings, because ruling C-4 forbids retiring or deleting a published
 * space while any case or suite still declares it (the manual retire edge and the expiry
 * sweep both ask this one gate, so one extension here covers both paths).
 * <p>Runs against the real dev database (SpringBootTest, no mocks; migrations applied
 * manually, so information_schema read-back is the only honest verification).
 */
@SpringBootTest(classes = CoreApplication.class)
class MockSpaceReferenceGateBindingTest {

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private MockSpaceReferenceGate gate;

    private boolean columnExists(String table, String column) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_name = :t AND column_name = :c
                """, new MapSqlParameterSource("t", table).addValue("c", column), Integer.class);
        return n != null && n > 0;
    }

    @Test
    void caseAndSuiteTablesCarryMockSpaceColumn() {
        assertTrue(columnExists("tp_test_case", "mock_space_id"),
                "tp_test_case must carry mock_space_id (declarative mock binding, T364)");
        assertTrue(columnExists("tp_regression_suite", "mock_space_id"),
                "tp_regression_suite must carry mock_space_id (declarative mock binding, T364)");
    }

    @Test
    void gateBlocksRetireWhileCaseOrSuiteStillBound() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long projectId = fixtureProject(suffix);
        long spaceId = insertSpace(suffix, projectId);
        long caseId = insertCase(suffix, spaceId, projectId);
        long suiteId = insertSuite(suffix, spaceId, projectId);
        try {
            List<String> reasons = gate.blockingReferences(spaceId);
            assertTrue(reasons.stream().anyMatch(r -> r.contains("用例")),
                    "gate must name bound cases, got: " + reasons);
            assertTrue(reasons.stream().anyMatch(r -> r.contains("套件")),
                    "gate must name bound suites, got: " + reasons);

            // unbind the case — only the suite reason may remain
            jdbc.update("UPDATE tp_test_case SET mock_space_id = NULL WHERE id = :id",
                    new MapSqlParameterSource("id", caseId));
            List<String> afterCase = gate.blockingReferences(spaceId);
            assertEquals(1, afterCase.size(), "suite alone must leave exactly one reason: " + afterCase);
            assertTrue(afterCase.get(0).contains("套件"));

            // unbind everything — free again
            jdbc.update("UPDATE tp_regression_suite SET mock_space_id = NULL WHERE id = :id",
                    new MapSqlParameterSource("id", suiteId));
            assertTrue(gate.blockingReferences(spaceId).isEmpty(),
                    "no bindings = no blockers");
        } finally {
            cleanup(caseId, suiteId, spaceId, projectId);
        }
    }

    /**
     * Fixture-owned project: sibling suites truncate tp_project in their
     * setup/teardown, so a pre-existing row cannot be trusted — create one per
     * test and remove it in cleanup (FK-safe: the gate's children are deleted first).
     */
    private long fixtureProject(String suffix) {
        jdbc.update("""
                INSERT INTO tp_project (name, code, status) VALUES (:n, :c, 1)
                """, new MapSqlParameterSource("n", "t364proj-" + suffix)
                .addValue("c", "t364p" + suffix));
        return jdbc.queryForObject("SELECT id FROM tp_project WHERE code = :c",
                new MapSqlParameterSource("c", "t364p" + suffix), Long.class);
    }

    private long insertSpace(String suffix, long projectId) {
        jdbc.update("""
                INSERT INTO tp_mock_space (project_id, name, space_key)
                VALUES (:pid, :n, :k)
                """, new MapSqlParameterSource("n", "t364gate-" + suffix)
                .addValue("k", "t364gate" + suffix)
                .addValue("pid", projectId));
        return jdbc.queryForObject(
                "SELECT id FROM tp_mock_space WHERE space_key = :k",
                new MapSqlParameterSource("k", "t364gate" + suffix), Long.class);
    }

    private long insertCase(String suffix, long spaceId, long projectId) {
        jdbc.update("""
                INSERT INTO tp_test_case (case_no, project_id, title, mock_space_id)
                VALUES (:no, :pid, :t, :sid)
                """, new MapSqlParameterSource("no", "T364-" + suffix)
                .addValue("t", "gate binding case " + suffix)
                .addValue("sid", spaceId)
                .addValue("pid", projectId));
        return jdbc.queryForObject("SELECT id FROM tp_test_case WHERE case_no = :no",
                new MapSqlParameterSource("no", "T364-" + suffix), Long.class);
    }

    private long insertSuite(String suffix, long spaceId, long projectId) {
        jdbc.update("""
                INSERT INTO tp_regression_suite (project_id, name, mock_space_id)
                VALUES (:pid, :n, :sid)
                """, new MapSqlParameterSource("n", "t364suite-" + suffix)
                .addValue("sid", spaceId)
                .addValue("pid", projectId));
        return jdbc.queryForObject("SELECT id FROM tp_regression_suite WHERE name = :n",
                new MapSqlParameterSource("n", "t364suite-" + suffix), Long.class);
    }

    private void cleanup(long caseId, long suiteId, long spaceId, long projectId) {
        jdbc.update("DELETE FROM tp_test_case WHERE id = :id", new MapSqlParameterSource("id", caseId));
        jdbc.update("DELETE FROM tp_regression_suite WHERE id = :id", new MapSqlParameterSource("id", suiteId));
        jdbc.update("DELETE FROM tp_mock_space WHERE id = :id", new MapSqlParameterSource("id", spaceId));
        jdbc.update("DELETE FROM tp_project WHERE id = :id", new MapSqlParameterSource("id", projectId));
    }
}
