package com.sixbean.testpike.core.apitest.domain.component;

import com.sixbean.testpike.core.apiaccess.domain.component.RowOwnershipReads;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Shared ownership reads behind the apitest row bridges.
 *
 * <p>Six governed entities in this family answer "which project owns this row" from three shapes
 * of the schema — four tables carry the owning project as their isolation column (served by the
 * shared read in {@link RowOwnershipReads}, never restated here), request rows hang under a
 * collection, and run rows hang under a request that in turn hangs under a collection. The two
 * containment statements live here once so the containment rule cannot drift between entities, and
 * every bridge stays a thin delegate that only names its entity. All reads answer a single value:
 * the owning project id, or null when the row (or its containment chain) does not resolve — the
 * bridges then fail closed through the door's contract.
 */
final class ApiRowOwnershipReads {

    private static final String SELECT_REQUEST_OWNER_PROJECT =
            "SELECT c.project_id FROM tp_apitest_request r"
                    + " JOIN tp_apitest_collection c ON c.id = r.collection_id"
                    + " WHERE r.id = :id";

    private static final String SELECT_RUN_OWNER_PROJECT =
            "SELECT c.project_id FROM tp_apitest_run ar"
                    + " JOIN tp_apitest_request r ON r.id = ar.request_id"
                    + " JOIN tp_apitest_collection c ON c.id = r.collection_id"
                    + " WHERE ar.id = :id";

    private ApiRowOwnershipReads() {
    }

    /** Owning project of a row whose table carries the isolation column; null when unresolvable. */
    static Long owningProjectOfIsolationColumnRow(NamedParameterJdbcTemplate jdbcTemplate,
                                                  String table, long rowId) {
        return RowOwnershipReads.owningProjectOfIsolationColumnRow(jdbcTemplate, table, rowId);
    }

    /** Owning project one containment edge up: request → collection → project. */
    static Long owningProjectOfRequest(NamedParameterJdbcTemplate jdbcTemplate, long requestId) {
        return RowOwnershipReads.owningProjectOf(jdbcTemplate, SELECT_REQUEST_OWNER_PROJECT, requestId);
    }

    /** Owning project two containment edges up: run → request → collection → project. */
    static Long owningProjectOfRun(NamedParameterJdbcTemplate jdbcTemplate, long runId) {
        return RowOwnershipReads.owningProjectOf(jdbcTemplate, SELECT_RUN_OWNER_PROJECT, runId);
    }
}
