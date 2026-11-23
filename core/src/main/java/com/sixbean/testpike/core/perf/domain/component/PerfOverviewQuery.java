package com.sixbean.testpike.core.perf.domain.component;

import com.sixbean.testpike.core.common.domain.component.DashboardRunRow;
import com.sixbean.testpike.core.common.domain.component.SliceWindowParams;
import com.sixbean.testpike.core.perf.api.dto.PerfOverview;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Single query source for load-test overview blocks (the perf domain owns the
 * tp_perf_scenario / tp_perf_run / tp_perf_metric reading; the reportcenter
 * dashboard feed consumes this component — it must never re-implement the
 * counting SQL, R40).
 *
 * <p>Three grouped statements over the whole visible project segment (no per-project
 * loop): one structural block (scenarios owned per project, independent of the
 * window), one run block (runs started inside [from, to) with the latest run's id
 * and status carried down by the window function) and one frame block (peak
 * throughput / p95 / error rate of the metric frames belonging to those in-window
 * runs). Project ownership is the only grouping key — both run and frame rows carry
 * their own project id, so nothing here reads a program id (the two "programId"
 * words in this system mean different things).
 *
 * <p>Projects with nothing to report simply have no result row — the caller fills an
 * honest quiet block via {@link PerfOverview#quiet(long)}.
 */
@Component
public class PerfOverviewQuery {

    private static final String STRUCTURE_SQL = """
            SELECT s.project_id, COUNT(*) AS scenario_count
            FROM tp_perf_scenario s
            WHERE s.project_id IN (:pids)
            GROUP BY s.project_id
            """;

    private static final String RUN_SQL = """
            WITH ranked AS (
                SELECT r.project_id, r.id AS run_id, r.status, r.started_at,
                       ROW_NUMBER() OVER (PARTITION BY r.project_id
                                          ORDER BY r.started_at DESC, r.id DESC) AS rn
                FROM tp_perf_run r
                WHERE r.project_id IN (:pids)
                  AND r.started_at >= :fromTime AND r.started_at < :toTime
            )
            SELECT project_id, COUNT(*) AS runs_in_window,
                   MAX(run_id) FILTER (WHERE rn = 1) AS latest_run_id,
                   MAX(status) FILTER (WHERE rn = 1) AS latest_status,
                   MAX(started_at) FILTER (WHERE rn = 1) AS latest_started_at
            FROM ranked
            GROUP BY project_id
            """;

    private static final String FRAME_SQL = """
            SELECT m.project_id, MAX(m.rps) AS peak_rps,
                   MAX(m.p95_ms) AS peak_p95_ms, MAX(m.error_rate) AS peak_error_rate
            FROM tp_perf_metric m
            JOIN tp_perf_run r ON r.id = m.run_id
            WHERE m.project_id IN (:pids)
              AND r.started_at >= :fromTime AND r.started_at < :toTime
            GROUP BY m.project_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public PerfOverviewQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
 * Overview blocks keyed by project id for the given window and project segment.
 *
 * @param projectIds visible project segment (empty = no query, empty map)
 * @param from window start (inclusive)
 * @param to window end (exclusive)
 */
    public Map<Long, PerfOverview> overviewBlocks(Collection<Long> projectIds,
                                                  LocalDateTime from, LocalDateTime to) {
        if (SliceWindowParams.isEmpty(projectIds)) {
            return Map.of();
        }
        MapSqlParameterSource params = SliceWindowParams.of(projectIds, from, to);
        Map<Long, PerfOverview> out = new HashMap<>();
        jdbc.query(STRUCTURE_SQL, params, rs -> {
            long pid = rs.getLong("project_id");
            out.put(pid, new PerfOverview(pid, rs.getLong("scenario_count"), 0L,
                    null, null, "grey", null, null, null));
        });
        jdbc.query(RUN_SQL, params, rs -> {
            long pid = rs.getLong("project_id");
            long runId = rs.getLong("latest_run_id");
            PerfOverview structural = out.getOrDefault(pid,
                    new PerfOverview(pid, 0L, 0L, null, null, "grey", null, null, null));
            out.put(pid, new PerfOverview(pid, structural.scenarioCount(),
                    rs.getLong("runs_in_window"), runId,
                    DashboardRunRow.startedAtOf(rs, "latest_started_at"),
                    DashboardRunRow.lightOf(rs, "latest_status"), null, null, null));
        });
        jdbc.query(FRAME_SQL, params, rs -> {
            long pid = rs.getLong("project_id");
            BigDecimal peakRps = rs.getBigDecimal("peak_rps");
            BigDecimal peakP95 = rs.getBigDecimal("peak_p95_ms");
            BigDecimal peakError = rs.getBigDecimal("peak_error_rate");
            PerfOverview sofar = out.getOrDefault(pid,
                    new PerfOverview(pid, 0L, 0L, null, null, "grey", null, null, null));
            out.put(pid, new PerfOverview(pid, sofar.scenarioCount(), sofar.runsInWindow(),
                    sofar.latestRunId(), sofar.latestRunAt(), sofar.light(),
                    peakRps, peakP95, peakError));
        });
        return out;
    }
}
