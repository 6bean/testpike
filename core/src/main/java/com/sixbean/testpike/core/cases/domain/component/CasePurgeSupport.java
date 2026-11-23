package com.sixbean.testpike.core.cases.domain.component;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * This component performs physical deletion and cascading cleanup of test cases (recycle-bin permanent deletion; reuse the same path as project purge).
 * <p>The batch-deletion FK checklist is validated by strict testing and evidenced by pg_constraint queries.
 * FK references (NO ACTION, child rows must be physically deleted) include tp_case_step, tp_script, and tp_review_log.
 * tp_case_requirement and tp_case_defect are also FK references; logical references without FK but where orphan rows are prohibited include tp_case_tag.
 * tp_case_version and tp_script_version are also logical references classified as history-not-deleted within the lifecycle.
 * Their snapshot retention semantics do not constrain orphan rows after physical purge, and the same ruling applies when mirroring tp_case_version into the checklist.
 * Nullable references (set null to preserve host rows) are tp_defect.case_id and tp_ai_task.case_id.
 * <p>Purging a single test case (recycle-bin permanent deletion) and purging a project share this component, and the same logic must not be duplicated.
 *
 */
@Component
public class CasePurgeSupport {

    /** Reference tables for physical row deletion are the union of FK references and logical orphan references, processed child before parent, with evidence from pg_constraint and columns queries. */
    private static final List<String> CHILD_TABLES = List.of(
            "tp_case_step", "tp_script", "tp_review_log", "tp_case_requirement", "tp_case_defect",
            "tp_case_tag", "tp_case_version", "tp_script_version", "tp_exec_task", "tp_plan_case",
            "tp_plan_deviation", "tp_training_sample");

    /** Nullable reference columns are set null without deleting host rows, while defects and generation tasks retain their own traceability. */
    private static final List<String> NULLABLE_REFS = List.of("tp_defect", "tp_ai_task");

    private final NamedParameterJdbcTemplate named;

    public CasePurgeSupport(NamedParameterJdbcTemplate named) {
        this.named = named;
    }

    /** Cascading cleanup is performed by a list of test case IDs (recycle-bin permanent deletion or emptying the recycle bin). */
    public void cleanByCaseIds(List<Long> caseIds) {
        if (caseIds == null || caseIds.isEmpty()) {
            return;
        }
        MapSqlParameterSource params = new MapSqlParameterSource("ids", caseIds);
        for (String table : CHILD_TABLES) {
            named.update("DELETE FROM " + table + " WHERE case_id IN (:ids)", params);
        }
        for (String table : NULLABLE_REFS) {
            named.update("UPDATE " + table + " SET case_id = NULL WHERE case_id IN (:ids)", params);
        }
    }

    /** Cascading cleanup is performed by project for all test-case dependent rows (reuse project purge; the SQL and single-case form share the same table-list source). */
    public void cleanByProject(long projectId) {
        MapSqlParameterSource params = new MapSqlParameterSource("pid", projectId);
        for (String table : CHILD_TABLES) {
            named.update("DELETE FROM " + table
                    + " WHERE case_id IN (SELECT id FROM tp_test_case WHERE project_id = :pid)", params);
        }
        for (String table : NULLABLE_REFS) {
            named.update("UPDATE " + table
                    + " SET case_id = NULL WHERE case_id IN (SELECT id FROM tp_test_case WHERE project_id = :pid)",
                    params);
        }
    }
}
