package com.sixbean.testpike.core.cluster.domain.service.impl;

import com.sixbean.testpike.core.cluster.api.dto.ExecutorPoolDemand;
import com.sixbean.testpike.core.cluster.api.dto.ExecutorPoolWatermarkPoint;
import com.sixbean.testpike.core.cluster.domain.service.ExecutorPoolWatermarkLedger;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Jdbc read/write side of the pool-watermark history.
 *
 * <p>Every sample row is written with the columns it observed (no derived fields), so the
 * curve replays what the autoscaler saw, not what it decided — decisions stay in the
 * scale-event ledger. The read window is clamped here in [1, {@link #MAX_CURVE_WINDOW}]:
 * one request must never stream the whole unbounded history into memory, and the paired
 * {@link #purgeExpired()} bounds the table itself by the retention key (R57: every row
 * created here has a scheduled recycle path, the retention bound is hot-tunable through
 * tp_sys_config and falls back to the shipped default when the row is absent).
 */
@Service
public class ExecutorPoolWatermarkLedgerImpl implements ExecutorPoolWatermarkLedger {

    /** Retention bound (days) tunable by operators on the settings page. */
    static final String K_RETENTION_DAYS = "cluster.autoscale-watermark-retention-days";

    /** Shipped default; identical to the V634 seed row on purpose (V631 posture). */
    static final int DEF_RETENTION_DAYS = 30;

    /** Safety cap for one curve read; the table beyond it stays reachable window by window. */
    static final int MAX_CURVE_WINDOW = 500;

    private static final String APPEND_SQL = """
            INSERT INTO tp_cluster_pool_watermark
                (executor_type, pending_dispatch_depth, current_replicas,
                 active_executors, p95_wait_seconds)
            VALUES (:executorType, :pendingDispatchDepth, :currentReplicas,
                    :activeExecutors, :p95WaitSeconds)
            """;

    private static final String RECENT_SQL = """
            SELECT executor_type, pending_dispatch_depth, current_replicas,
                   active_executors, p95_wait_seconds,
                   EXTRACT(epoch FROM sampled_at)::bigint AS sampled_epoch_seconds
            FROM tp_cluster_pool_watermark
            WHERE CAST(:executorType AS VARCHAR) IS NULL OR executor_type = CAST(:executorType AS VARCHAR)
            ORDER BY sampled_at DESC, id DESC
            LIMIT :limit
            """;

    private static final String PURGE_SQL = """
            DELETE FROM tp_cluster_pool_watermark
            WHERE sampled_at < now() - make_interval(days => :days)
            """;

    private final NamedParameterJdbcTemplate namedJdbcTemplate;
    private final ConfigCacheHolder configCacheHolder;

    public ExecutorPoolWatermarkLedgerImpl(NamedParameterJdbcTemplate namedJdbcTemplate,
                                           ConfigCacheHolder configCacheHolder) {
        this.namedJdbcTemplate = namedJdbcTemplate;
        this.configCacheHolder = configCacheHolder;
    }

    @Override
    public void appendAll(List<ExecutorPoolDemand> demands) {
        if (demands == null || demands.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] rows = demands.stream()
                .map(d -> new MapSqlParameterSource()
                        .addValue("executorType", d.executorType())
                        .addValue("pendingDispatchDepth", d.pendingDispatchDepth())
                        .addValue("currentReplicas", d.currentReplicas())
                        .addValue("activeExecutors", d.activeExecutors())
                        .addValue("p95WaitSeconds", d.p95WaitSeconds()))
                .toArray(MapSqlParameterSource[]::new);
        namedJdbcTemplate.batchUpdate(APPEND_SQL, rows);
    }

    @Override
    public List<ExecutorPoolWatermarkPoint> recentSeries(String executorType, int limit) {
        int size = Math.max(1, Math.min(limit, MAX_CURVE_WINDOW));
        String filter = executorType == null || executorType.isBlank() ? null : executorType;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("executorType", filter)
                .addValue("limit", size);
        return namedJdbcTemplate.query(RECENT_SQL, params, (rs, rowNum) -> new ExecutorPoolWatermarkPoint(
                rs.getString("executor_type"),
                rs.getInt("pending_dispatch_depth"),
                rs.getInt("current_replicas"),
                rs.getInt("active_executors"),
                rs.getDouble("p95_wait_seconds"),
                rs.getLong("sampled_epoch_seconds")));
    }

    @Override
    public int purgeExpired() {
        return namedJdbcTemplate.update(PURGE_SQL,
                new MapSqlParameterSource("days", retentionDays()));
    }

    /** Hot-tunable retention bound; malformed or absent config degrades to the shipped default. */
    private int retentionDays() {
        try {
            String raw = configCacheHolder.get(K_RETENTION_DAYS);
            if (raw == null || raw.isBlank()) {
                return DEF_RETENTION_DAYS;
            }
            int parsed = Integer.parseInt(raw.trim());
            return parsed < 1 || parsed > 3650 ? DEF_RETENTION_DAYS : parsed;
        } catch (RuntimeException ex) {
            // A cache miss shape must never break the recycle path; fall back to the bound.
            return DEF_RETENTION_DAYS;
        }
    }
}
