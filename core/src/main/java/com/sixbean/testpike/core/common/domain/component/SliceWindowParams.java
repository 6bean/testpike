package com.sixbean.testpike.core.common.domain.component;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collection;

/**
 * Parameter bag for the dashboard's per-project window queries (project segment +
 * half-open window). Two consumers today: the journey traffic-light query and the
 * load-test overview query, both called by the project-set board feed with the same
 * visible segment and the same window — the assembly lives here once so a third block
 * cannot drift onto a different window caliber (R40).
 */
public final class SliceWindowParams {

    private SliceWindowParams() {
    }

    /**
 * Named parameters for a window segment query: {@code pids} (the visible project
 * ids), {@code fromTime} (inclusive) and {@code toTime} (exclusive).
 */
    public static MapSqlParameterSource of(Collection<Long> projectIds,
                                          LocalDateTime from, LocalDateTime to) {
        return new MapSqlParameterSource()
                .addValue("pids", projectIds)
                .addValue("fromTime", Timestamp.valueOf(from))
                .addValue("toTime", Timestamp.valueOf(to));
    }

    /** True when the segment has nothing to read — callers answer with an empty map, no SQL. */
    public static boolean isEmpty(Collection<Long> projectIds) {
        return projectIds == null || projectIds.isEmpty();
    }
}
