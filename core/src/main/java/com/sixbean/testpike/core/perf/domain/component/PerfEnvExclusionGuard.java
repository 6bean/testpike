package com.sixbean.testpike.core.perf.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * GA-2 environment execution lock: one environment carries at most one in-flight
 * load at a time (a running perf run or an executing regression batch).
 * <p>Why here: perf runs and regression batches used to hit the same environment
 * simultaneously with zero protection, so pressure from one side poisoned the
 * assertions of the other. The launch point is the only place that knows an
 * environment is about to be loaded, so the occupant check runs inside the launch
 * transaction, after the safety gate and before the run row exists — a rejection
 * therefore rolls back to zero residue.
 * <p>Race posture (same advisory-lock baseline as CaseServiceImpl numbering):
 * {@code pg_advisory_xact_lock(base + envId)} serialises "find occupant → insert
 * run row → dispatch" for one environment across concurrent launches; the lock is
 * released at commit, and under READ COMMITTED the next launch sees the row the
 * previous launch committed.
 * <p>Rejection wording is human-readable and names the occupant. For an active
 * perf run it also gives the estimated release moment (started_at + declared run
 * duration + the same hot-updatable budget margin the dispatch envelope uses, so
 * the promise shown to the user and the executor watchdog agree). A legacy run
 * whose pinned snapshot carries no resolvable duration gets an honest statement
 * instead of a fabricated time.
 */
@Component
public class PerfEnvExclusionGuard {

    private static final Logger log = LoggerFactory.getLogger(PerfEnvExclusionGuard.class);

    /** Advisory-lock key base for the perf environment lock (pg_advisory_xact_lock
     *  is a database-wide namespace — keep this base unique; CaseServiceImpl uses
     *  20260908_148L for per-project case numbering). */
    static final long ENV_LOCK_KEY_BASE = 20261023_574L;

    private static final DateTimeFormatter RELEASE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final PerfConfigReader perfConfigReader;

    public PerfEnvExclusionGuard(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper,
                                 PerfConfigReader perfConfigReader) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.perfConfigReader = perfConfigReader;
    }

    /**
     * Serialises concurrent launches for one environment and returns the current
     * occupant (active perf run first, then an executing regression batch), or null
     * when the environment is free. Call sites are transactional — the advisory
     * transaction lock covers exactly the critical section (check → insert →
     * dispatch) and is released at commit.
     */
    public PerfEnvOccupant lockAndFindOccupant(long envId) {
        jdbc.getJdbcOperations().queryForObject(
                "SELECT pg_advisory_xact_lock(?)::text", String.class, ENV_LOCK_KEY_BASE + envId);
        String envName = jdbc.queryForObject("SELECT name FROM tp_project_env WHERE id = :e",
                new MapSqlParameterSource("e", envId), String.class);
        PerfEnvOccupant occupant = findPerfOccupant(envId);
        if (occupant == null) {
            occupant = findBatchOccupant(envId);
        }
        if (occupant == null) {
            return null;
        }
        return withEnvName(occupant, envName);
    }

    private PerfEnvOccupant findPerfOccupant(long envId) {
        // GB-1: occupancy follows the run's PINNED environment (snapshot envId), not
        // tp_perf_scenario.env_id — a launch that overrode its environment must lock
        // the environment it actually presses. Runs whose snapshot predates the envId
        // key fall back to the scenario default (coalesce = honest legacy read, the
        // scenario row is the only source those runs ever had).
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.name AS scenario_name, r.started_at, r.pressure_snapshot::text AS snapshot"
                        + " FROM tp_perf_run r JOIN tp_perf_scenario s ON s.id = r.scenario_id"
                        + " WHERE COALESCE((r.pressure_snapshot->>'envId')::bigint, s.env_id) = :e"
                        + " AND r.status = 1 ORDER BY r.id LIMIT 1",
                new MapSqlParameterSource("e", envId));
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        LocalDateTime startedAt = ((Timestamp) row.get("started_at")).toLocalDateTime();
        long durationSeconds = durationOfSnapshot((String) row.get("snapshot"));
        if (durationSeconds <= 0) {
            log.info("GA-2 occupant run on env {} has no resolvable duration in its pinned "
                    + "snapshot; rejection falls back to honest wording, no fabricated time", envId);
        }
        return PerfEnvOccupant.perfRun((String) row.get("scenario_name"), startedAt,
                durationSeconds, perfConfigReader.budgetMarginMinutes());
    }

    private PerfEnvOccupant findBatchOccupant(long envId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT name FROM tp_exec_batch WHERE env_id = :e AND status = 4"
                        + " ORDER BY id LIMIT 1",
                new MapSqlParameterSource("e", envId));
        if (rows.isEmpty()) {
            return null;
        }
        Object name = rows.get(0).get("name");
        return PerfEnvOccupant.batch(name == null ? "(未命名批次)" : String.valueOf(name));
    }

    /** Declared run duration from the pinned snapshot; delegates to the shared
     *  snapshot-duration reader in {@link PerfBudgetPolicy} (launch ceiling,
     *  release estimate and the terminal data-loss check all ride one source). */
    private long durationOfSnapshot(String snapshotJson) {
        return (long) PerfBudgetPolicy.durationSecondsOfSnapshot(objectMapper, snapshotJson);
    }

    /** Human-readable rejection naming the occupant; perf occupants additionally
     *  carry the estimated release moment when the duration is resolvable. */
    public String formatRejection(PerfEnvOccupant occupant) {
        if (occupant.batchName() != null) {
            return "压测运行启动被环境执行锁拦截：环境「" + occupant.envName()
                    + "」正被执行中的回归批次「" + occupant.batchName()
                    + "」占用，请等待批次结束后再发起压测";
        }
        String base = "压测运行启动被环境执行锁拦截：环境「" + occupant.envName()
                + "」已有运行中的压测（场景「" + occupant.scenarioName() + "」，发起于 "
                + occupant.startedAt().format(RELEASE_FORMAT) + "）";
        if (occupant.hasReleaseEstimate()) {
            return base + "，预计 " + occupant.estimatedReleaseAt().format(RELEASE_FORMAT)
                    + " 释放（运行时长+缓冲），请等待结束或人工停止后再发起";
        }
        return base + "，该运行的钉版快照无时长信息、无法给出预计释放时刻，"
                + "请等待其结束或人工停止后再发起";
    }

    private PerfEnvOccupant withEnvName(PerfEnvOccupant occupant, String envName) {
        if (occupant.batchName() != null) {
            return PerfEnvOccupant.batch(occupant.batchName(), envName);
        }
        return PerfEnvOccupant.perfRun(occupant.scenarioName(), occupant.startedAt(),
                occupant.durationSeconds(), occupant.marginMinutes(), envName);
    }
}
