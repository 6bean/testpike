package com.sixbean.testpike.core.journey.domain.component;

import com.sixbean.testpike.core.common.domain.component.DashboardRunRow;
import com.sixbean.testpike.core.common.domain.component.SliceWindowParams;
import com.sixbean.testpike.core.journey.api.dto.JourneyTrafficLight;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Single query source for journey traffic-light blocks (the journey domain owns
 * the tp_journey / tp_journey_run reading; the reportcenter dashboard feed
 * consumes this component — it must never re-implement the counting SQL, R40).
 *
 * <p>Two grouped statements over the whole visible project segment (no per-project
 * loop): one structural block (journeys + segments owned per project, independent
 * of the window) and one window block (runs that touched each project inside
 * [from, to) with the latest run's id carried down to the status join). The
 * project id is the only grouping key — the journey table's program_id column is
 * the project set, matching the caller segment by construction (pitfall 26).
 *
 * <p>Projects without journeys simply have no result row — the caller fills an
 * honest grey block via {@link JourneyTrafficLight#grey(long)}.
 */
@Component
public class JourneyTrafficLightQuery {

    private static final String STRUCTURE_SQL = """
            SELECT s.project_id,
                   COUNT(DISTINCT s.journey_id) AS journey_count,
                   COUNT(*) AS segment_count
            FROM tp_journey_segment s
            JOIN tp_journey j ON j.id = s.journey_id
            WHERE j.program_id = :programId AND s.project_id IN (:pids)
            GROUP BY s.project_id
            """;

    private static final String WINDOW_SQL = """
            WITH touched AS (
                SELECT rs.project_id, r.id AS run_id, r.status, r.started_at,
                       ROW_NUMBER() OVER (PARTITION BY rs.project_id ORDER BY r.started_at DESC) AS rn
                FROM tp_journey_run_segment rs
                JOIN tp_journey_run r ON r.id = rs.run_id
                JOIN tp_journey j ON j.id = r.journey_id
                WHERE j.program_id = :programId AND rs.project_id IN (:pids)
                  AND r.started_at >= :fromTime AND r.started_at < :toTime
            ), latest AS (
                SELECT project_id, run_id, status, started_at FROM touched WHERE rn = 1
            )
            SELECT t.project_id, COUNT(*) AS runs_in_window,
                   l.run_id AS latest_run_id, l.status AS latest_status, l.started_at AS latest_started_at
            FROM touched t
            JOIN latest l ON l.project_id = t.project_id
            GROUP BY t.project_id, l.run_id, l.status, l.started_at
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JourneyTrafficLightQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
 * Traffic-light blocks keyed by project id for the given window and project set.
 *
 * @param programId project set id (tp_program primary key — NOT a journey plan id)
 * @param projectIds visible project segment (empty = no query, empty map)
 * @param from window start (inclusive)
 * @param to window end (exclusive)
 */
    public Map<Long, JourneyTrafficLight> trafficLights(long programId, Collection<Long> projectIds,
                                                        LocalDateTime from, LocalDateTime to) {
        if (SliceWindowParams.isEmpty(projectIds)) {
            return Map.of();
        }
        MapSqlParameterSource params = SliceWindowParams.of(projectIds, from, to)
                .addValue("programId", programId);
        Map<Long, long[]> structure = new HashMap<>();
        jdbc.query(STRUCTURE_SQL, params, rs -> {
            structure.put(rs.getLong("project_id"),
                    new long[] {rs.getLong("journey_count"), rs.getLong("segment_count")});
        });
        Map<Long, JourneyTrafficLight> out = new HashMap<>();
        jdbc.query(WINDOW_SQL, params, rs -> {
            long pid = rs.getLong("project_id");
            long[] counts = structure.getOrDefault(pid, new long[] {0L, 0L});
            out.put(pid, new JourneyTrafficLight(pid, counts[0], counts[1],
                    rs.getLong("runs_in_window"),
                    DashboardRunRow.startedAtOf(rs, "latest_started_at"),
                    DashboardRunRow.lightOf(rs, "latest_status")));
        });
        // Windowless-but-structural projects still read grey with their counts
        // (a journey that exists but never ran in the window is a fact, not an absence).
        structure.forEach((pid, counts) -> out.putIfAbsent(pid,
                new JourneyTrafficLight(pid, counts[0], counts[1], 0L, null, "grey")));
        return out;
    }
}
