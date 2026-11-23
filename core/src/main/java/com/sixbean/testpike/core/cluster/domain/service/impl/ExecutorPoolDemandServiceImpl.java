package com.sixbean.testpike.core.cluster.domain.service.impl;

import com.sixbean.testpike.core.cluster.api.dto.ExecutorPoolDemand;
import com.sixbean.testpike.core.cluster.domain.service.ExecutorPoolDemandService;
import com.sixbean.testpike.core.cluster.domain.service.ExecutorPoolScaleEventLedger;
import com.sixbean.testpike.core.executor.api.dto.ExecutorInfo;
import com.sixbean.testpike.core.executor.domain.component.AppAutoDispatchSupport;
import com.sixbean.testpike.core.perf.domain.component.PerfEngineRoutingSupport;
import com.sixbean.testpike.core.executor.domain.service.ExecutorService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles the autoscaler demand spectrum from live fleet state (T294c wiring).
 *
 * <p>Pools are keyed by the end-type tokens reported by ONLINE executors; the online
 * semantics (status and heartbeat window) are consumed from
 * {@link ExecutorService#listOnline()} as a single source, never re-derived here.
 * For the pending backlog, a task counts into a pool's demand only when at least one
 * online executor of that pool could actually claim it: the app-auto and perf
 * capability-routing predicates are reused as single sources
 * ({@link AppAutoDispatchSupport#shouldSkipForExecutor} /
 * {@link PerfEngineRoutingSupport#shouldSkipForExecutor}), mirroring the claim-side
 * candidate posture so the autoscaler never scales a pool for work that pool would
 * itself skip.
 *
 * <p>Boundary behaviour: the backlog is scanned through a bounded window; beyond the
 * window the depth saturates, which is safe for the decision because the guardrail
 * ceiling clamps desired replicas well below any windowed depth. Executors with a blank
 * end-type declaration back no pool (nothing honest to scale). The last scale event
 * epoch comes from the scale-event ledger (single source
 * {@link ExecutorPoolScaleEventLedger#latestScaleEpochSeconds}); an un-scaled pool reads
 * 0, so the cooldown guard degrades to "never recently scaled" rather than suppressing
 * scales wrongly.
 */
@Service
public class ExecutorPoolDemandServiceImpl implements ExecutorPoolDemandService {

    private static final int PENDING_TASK_STATUS = 1;
    private static final int CANCELLED_BATCH_STATUS = 5;
    private static final int BACKLOG_SCAN_WINDOW = 500;

    private static final String BACKLOG_SQL = """
            SELECT t.action_json, EXTRACT(epoch FROM now() - t.created_at) AS wait_seconds
            FROM tp_exec_task t
            LEFT JOIN tp_exec_batch b ON b.id = t.batch_id
            WHERE t.status = :pending AND b.status IS DISTINCT FROM :cancelled
            ORDER BY t.id
            LIMIT :scanWindow
            """;

    private final ExecutorService executorService;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;
    private final AppAutoDispatchSupport appAutoDispatchSupport;
    private final PerfEngineRoutingSupport perfEngineRoutingSupport;
    private final ExecutorPoolScaleEventLedger scaleEventLedger;

    public ExecutorPoolDemandServiceImpl(ExecutorService executorService,
                                         NamedParameterJdbcTemplate namedJdbcTemplate,
                                         AppAutoDispatchSupport appAutoDispatchSupport,
                                         PerfEngineRoutingSupport perfEngineRoutingSupport,
                                         ExecutorPoolScaleEventLedger scaleEventLedger) {
        this.executorService = executorService;
        this.namedJdbcTemplate = namedJdbcTemplate;
        this.appAutoDispatchSupport = appAutoDispatchSupport;
        this.perfEngineRoutingSupport = perfEngineRoutingSupport;
        this.scaleEventLedger = scaleEventLedger;
    }

    @Override
    public List<ExecutorPoolDemand> snapshotPools() {
        Map<String, List<ExecutorInfo>> pools = groupOnlinePools();
        if (pools.isEmpty()) {
            return List.of();
        }
        List<String> backlogActions = new ArrayList<>();
        List<Double> backlogWaits = new ArrayList<>();
        readBacklog(backlogActions, backlogWaits);

        List<ExecutorPoolDemand> snapshot = new ArrayList<>();
        for (Map.Entry<String, List<ExecutorInfo>> pool : pools.entrySet()) {
            List<Double> claimableWaits = new ArrayList<>();
            for (int i = 0; i < backlogActions.size(); i++) {
                if (claimableByAny(pool.getValue(), backlogActions.get(i))) {
                    claimableWaits.add(backlogWaits.get(i));
                }
            }
            snapshot.add(new ExecutorPoolDemand(
                    pool.getKey(),
                    claimableWaits.size(),
                    pool.getValue().size(),
                    countActive(pool.getValue()),
                    percentile95(claimableWaits),
                    scaleEventLedger.latestScaleEpochSeconds(pool.getKey())));
        }
        return snapshot;
    }

    /** Online executors bucketed by every end-type token they declare. */
    private Map<String, List<ExecutorInfo>> groupOnlinePools() {
        Map<String, List<ExecutorInfo>> pools = new LinkedHashMap<>();
        for (ExecutorInfo executor : executorService.listOnline()) {
            String endTypes = executor.endTypes();
            if (endTypes == null || endTypes.isBlank()) {
                continue;
            }
            for (String token : endTypes.split(",")) {
                String type = token.trim().toLowerCase();
                if (!type.isEmpty()) {
                    pools.computeIfAbsent(type, key -> new ArrayList<>()).add(executor);
                }
            }
        }
        return pools;
    }

    private void readBacklog(List<String> actions, List<Double> waits) {
        namedJdbcTemplate.query(BACKLOG_SQL,
                new MapSqlParameterSource()
                        .addValue("pending", PENDING_TASK_STATUS)
                        .addValue("cancelled", CANCELLED_BATCH_STATUS)
                        .addValue("scanWindow", BACKLOG_SCAN_WINDOW),
                rs -> {
                    actions.add(rs.getString("action_json"));
                    waits.add(rs.getDouble("wait_seconds"));
                });
    }

    /** Pool-level claimability: any online executor of the pool must not skip the task. */
    private boolean claimableByAny(List<ExecutorInfo> pool, String actionJson) {
        for (ExecutorInfo executor : pool) {
            boolean appSkipped = appAutoDispatchSupport
                    .shouldSkipForExecutor(actionJson, executor.capabilities());
            boolean perfSkipped = perfEngineRoutingSupport
                    .shouldSkipForExecutor(actionJson, executor.capabilities());
            if (!appSkipped && !perfSkipped) {
                return true;
            }
        }
        return false;
    }

    private int countActive(List<ExecutorInfo> pool) {
        int active = 0;
        for (ExecutorInfo executor : pool) {
            if (executor.runningCnt() > 0) {
                active++;
            }
        }
        return active;
    }

    /** Linear-interpolated 95th percentile, matching percentile_cont semantics. */
    private static double percentile95(List<Double> waits) {
        if (waits.isEmpty()) {
            return 0.0;
        }
        List<Double> sorted = new ArrayList<>(waits);
        sorted.sort(Double::compare);
        double position = 0.95 * (sorted.size() - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        double fraction = position - lower;
        return sorted.get(lower) + (sorted.get(upper) - sorted.get(lower)) * fraction;
    }
}
