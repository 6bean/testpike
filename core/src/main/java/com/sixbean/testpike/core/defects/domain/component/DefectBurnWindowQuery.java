package com.sixbean.testpike.core.defects.domain.component;

import com.sixbean.testpike.core.defects.api.dto.DefectBurnWindow;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Single query source for defect burndown windows (defect domain owns the
 * tp_defect reading; the reportcenter dashboard feed consumes this component —
 * it must never re-implement the counting SQL, R40).
 *
 * <p>One grouped statement over the whole visible project set (no per-project
 * loop). Caliber: open = status &lt;&gt; 5 (dashboard precedent); a closed defect's
 * closing moment rides updated_at because the state machine forbids edits after
 * closing (only reopening, which moves the row out of the closed state).
 * Projects without defects simply have no result row — the caller fills an
 * honest all-zero block via {@link DefectBurnWindow#zeros(long)}.
 */
@Component
public class DefectBurnWindowQuery {

    private static final String BURN_SQL = """
            SELECT project_id,
                   SUM(CASE WHEN created_at < :fromTime
                             AND (status <> 5 OR updated_at >= :fromTime)
                            THEN 1 ELSE 0 END) AS open_at_start,
                   SUM(CASE WHEN created_at >= :fromTime AND created_at < :toTime
                            THEN 1 ELSE 0 END) AS created_within,
                   SUM(CASE WHEN status = 5 AND updated_at >= :fromTime AND updated_at < :toTime
                            THEN 1 ELSE 0 END) AS closed_within,
                   SUM(CASE WHEN created_at < :toTime
                             AND (status <> 5 OR updated_at >= :toTime)
                            THEN 1 ELSE 0 END) AS open_at_end
            FROM tp_defect
            WHERE project_id IN (:pids)
            GROUP BY project_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public DefectBurnWindowQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
 * Burndown blocks keyed by project id for the given window and project set.
 *
 * @param projectIds visible project segment (empty = no query, empty map)
 * @param from window start (inclusive)
 * @param to window end (exclusive)
 */
    public Map<Long, DefectBurnWindow> burnWindows(Collection<Long> projectIds,
                                                   LocalDateTime from, LocalDateTime to) {
        if (projectIds == null || projectIds.isEmpty()) {
            return Map.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("fromTime", Timestamp.valueOf(from))
                .addValue("toTime", Timestamp.valueOf(to))
                .addValue("pids", projectIds);
        Map<Long, DefectBurnWindow> out = new HashMap<>();
        jdbc.query(BURN_SQL, params, rs -> {
            long pid = rs.getLong("project_id");
            out.put(pid, new DefectBurnWindow(pid, rs.getLong("open_at_start"),
                    rs.getLong("created_within"), rs.getLong("closed_within"),
                    rs.getLong("open_at_end")));
        });
        return out;
    }
}
