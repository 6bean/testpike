package com.sixbean.testpike.core.apiaccess.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Shared detail-path reads for the row-ownership bridges.
 *
 * <p>Every family that delegates "which projects gate this row" to an existing service read faces
 * the same three outcomes — row found, row not-found, unrelated service problem — and each bridge
 * restating that fold lets the fail-closed behaviour drift per family. One logic, one source: a
 * not-found service verdict folds to an absent row so the door refuses the call, while any other
 * service problem is rethrown untouched because it is not an ownership answer.
 */
public final class RowOwnershipReads {

    private static final String SELECT_OWNER_PROJECT_OF_ISOLATION_COLUMN_ROW =
            "SELECT project_id FROM %s WHERE id = :id";

    private RowOwnershipReads() {
    }

    /** Reads the row through its existing detail path; null when the service says it does not exist. */
    public static <T> T rowOrAbsent(Supplier<T> detailRead) {
        try {
            return detailRead.get();
        } catch (ServiceException notFound) {
            if (notFound.getCode() != null && notFound.getCode() == BizErrorCode.NOT_FOUND) {
                return null;
            }
            throw notFound;
        }
    }

    /** Wraps an owning project id into the door's gate set; an absent owner stays absent (fail closed). */
    public static List<Long> gatedBy(Long projectId) {
        return projectId == null ? null : List.of(projectId);
    }

    /**
     * Owning project behind a single-valued ownership query (the query must name its row parameter
     * {@code :id} and select exactly the owning project id). No row is not an ownership answer, so
     * an empty result is null and the door fails closed.
     */
    public static Long owningProjectOf(NamedParameterJdbcTemplate jdbcTemplate, String sql, long rowId) {
        List<Long> rows = owningProjectRows(jdbcTemplate, sql, rowId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * The shape shared by every family whose table carries the owning project as its isolation
     * column. Restating the statement per family would let the column name drift between bridges,
     * so it is written once here and each bridge only names its table.
     */
    public static Long owningProjectOfIsolationColumnRow(NamedParameterJdbcTemplate jdbcTemplate,
                                                         String table, long rowId) {
        return owningProjectOf(jdbcTemplate,
                String.format(SELECT_OWNER_PROJECT_OF_ISOLATION_COLUMN_ROW, table), rowId);
    }

    /**
     * Visibility tiers of a row whose owning-project column may legitimately be empty — the shared
     * bucket some families run (a device pool or a reservation of a shared slot belongs to no
     * project and every logged-in user may read it, exactly as its page renders it). Three
     * verdicts, never conflated: no row → null (fail closed), row without an owner → empty set
     * (system-level tier), row with an owner → that one project.
     */
    public static List<Long> tiersOfNullableIsolationColumnRow(NamedParameterJdbcTemplate jdbcTemplate,
                                                               String table, long rowId) {
        List<Long> rows = owningProjectRows(jdbcTemplate,
                String.format(SELECT_OWNER_PROJECT_OF_ISOLATION_COLUMN_ROW, table), rowId);
        if (rows.isEmpty()) {
            return null;
        }
        Long owner = rows.get(0);
        return owner == null ? List.of() : List.of(owner);
    }

    private static List<Long> owningProjectRows(NamedParameterJdbcTemplate jdbcTemplate,
                                                String sql, long rowId) {
        return jdbcTemplate.queryForList(sql, Map.of("id", rowId), Long.class);
    }
}
