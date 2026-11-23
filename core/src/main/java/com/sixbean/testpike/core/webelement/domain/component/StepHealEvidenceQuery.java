package com.sixbean.testpike.core.webelement.domain.component;

import com.sixbean.testpike.core.webelement.api.dto.HealEvidencePage;
import com.sixbean.testpike.core.webelement.api.dto.StepHealEvidenceView;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;

/**
 * Read doors over tp_exec_step_heal_evidence . Two
 * consumer surfaces share these rows as their single source: the execution
 * report badges healed steps per task, and the Page Element Library detail
 * lists recent heals per element. Queries never recompute or copy numbers —
 * the execution record stays authoritative.
 * <p>Bound posture: element-scope reads are paged with a server-side
 * clamped page size (worst-case-scale rule: no unbounded list doors);
 * task-scope reads are bounded by the task FK cascade in V554, so one
 * task's evidence can never outlive the task row.
 */
@Component
public class StepHealEvidenceQuery {

    /** Hard ceiling for one element-page request (list-visibility rule). */
    static final int MAX_PAGE_SIZE = 100;
    /** Default page size when the caller asks for something illegal. */
    static final int DEFAULT_PAGE_SIZE = 20;

    private static final String SELECT_COLUMNS = """
            SELECT id, task_id, element_id, step_no, healed, first_anchor_failed,
                   from_anchor_type, from_anchor_value,
                   used_anchor_type, used_anchor_value,
                   viewport, ledger_revision, similarity_score, created_at
            """;

    private static final RowMapper<StepHealEvidenceView> ROW_MAPPER = (rs, rowNum) -> {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new StepHealEvidenceView(
                rs.getLong("id"),
                rs.getLong("task_id"),
                rs.getObject("element_id") == null ? null : rs.getLong("element_id"),
                rs.getObject("step_no") == null ? null : rs.getInt("step_no"),
                (Boolean) rs.getObject("healed"),
                (Boolean) rs.getObject("first_anchor_failed"),
                rs.getString("from_anchor_type"),
                rs.getString("from_anchor_value"),
                rs.getString("used_anchor_type"),
                rs.getString("used_anchor_value"),
                rs.getString("viewport"),
                rs.getObject("ledger_revision") == null ? null : rs.getInt("ledger_revision"),
                rs.getObject("similarity_score") == null ? null : rs.getDouble("similarity_score"),
                createdAt == null ? null : createdAt.toLocalDateTime());
    };

    private final NamedParameterJdbcTemplate namedJdbcTemplate;

    public StepHealEvidenceQuery(NamedParameterJdbcTemplate namedJdbcTemplate) {
        this.namedJdbcTemplate = namedJdbcTemplate;
    }

    /**
     * All evidence rows of one execution task, step order first (nulls
     * last), insertion id as tie-break. Carries the owning project id from
     * the task JOIN for the admin-side membership guard; an unknown task
     * answers an empty page with a null project id, never an error.
     */
    public HealEvidencePage byTask(long taskId) {
        List<Long> projectIds = namedJdbcTemplate.queryForList(
                "SELECT project_id FROM tp_exec_task WHERE id = :taskId",
                new MapSqlParameterSource("taskId", taskId), Long.class);
        Long projectId = projectIds.isEmpty() ? null : projectIds.get(0);
        List<StepHealEvidenceView> items = namedJdbcTemplate.query(
                SELECT_COLUMNS + """
                        FROM tp_exec_step_heal_evidence
                        WHERE task_id = :taskId
                        ORDER BY step_no ASC NULLS LAST, id ASC
                        """,
                new MapSqlParameterSource("taskId", taskId), ROW_MAPPER);
        return new HealEvidencePage(items, items.size(), 1, items.size(), projectId);
    }

    /**
     * Recent heals for one ledger element, newest first, bounded paging.
     * Illegal page/pageSize inputs degrade to (1, 20); oversized requests
     * clamp at 100 so one drawer open never scans the whole table.
     */
    public HealEvidencePage byElement(long elementId, int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safeSize = pageSize < 1
                ? DEFAULT_PAGE_SIZE
                : Math.min(pageSize, MAX_PAGE_SIZE);
        Long total = namedJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_exec_step_heal_evidence WHERE element_id = :elementId",
                new MapSqlParameterSource("elementId", elementId), Long.class);
        long count = total == null ? 0L : total;
        List<StepHealEvidenceView> items = namedJdbcTemplate.query(
                SELECT_COLUMNS + """
                        FROM tp_exec_step_heal_evidence
                        WHERE element_id = :elementId
                        ORDER BY id DESC
                        LIMIT :limit OFFSET :offset
                        """,
                new MapSqlParameterSource()
                        .addValue("elementId", elementId)
                        .addValue("limit", safeSize)
                        .addValue("offset", (long) (safePage - 1) * safeSize),
                ROW_MAPPER);
        return new HealEvidencePage(items, count, safePage, safeSize, null);
    }
}
