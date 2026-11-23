package com.sixbean.testpike.core.tool.domain.component;

import com.sixbean.testpike.core.tool.infrastructure.dataobject.QueryTargetRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Loader for the read-only query-target allow list (tp_query_target, V333).
 * Registry row = the callable-object boundary #1 of the QuerySpec DSL: a
 * target absent here is refused at compile time, so a hallucinated table or
 * column can never reach the database.
 * <p>No cache (same posture as ProjectRoleResolver): the registry is read per
 * query, so registering a new target takes effect on the very next request
 * without a restart or a second lifecycle source.
 */
@Component
public class QueryTargetRegistry {

    private final JdbcTemplate jdbcTemplate;

    public QueryTargetRegistry(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Registry lookup by the model-facing target key. */
    public Optional<QueryTargetRow> find(String targetKey) {
        if (targetKey == null || targetKey.isBlank()) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
                "SELECT target_key, base_table, scope_column, dimension_columns,"
                        + " measure_columns, hard_filter, max_rows"
                        + " FROM tp_query_target WHERE target_key = ?",
                (rs, rowNum) -> new QueryTargetRow(
                        rs.getString("target_key"),
                        rs.getString("base_table"),
                        rs.getString("scope_column"),
                        rs.getString("dimension_columns"),
                        rs.getString("measure_columns"),
                        rs.getString("hard_filter"),
                        rs.getInt("max_rows")),
                targetKey).stream().findFirst();
    }

    /**
 * Whole registry in stable key order — the model-facing target vocabulary
 * (AO batch P3 the dialog tool lists callable targets so
 * the model composes specs against real keys instead of guessing).
 */
    public List<QueryTargetRow> findAll() {
        return jdbcTemplate.query(
                "SELECT target_key, base_table, scope_column, dimension_columns,"
                        + " measure_columns, hard_filter, max_rows"
                        + " FROM tp_query_target ORDER BY target_key",
                (rs, rowNum) -> new QueryTargetRow(
                        rs.getString("target_key"),
                        rs.getString("base_table"),
                        rs.getString("scope_column"),
                        rs.getString("dimension_columns"),
                        rs.getString("measure_columns"),
                        rs.getString("hard_filter"),
                        rs.getInt("max_rows")));
    }
}
