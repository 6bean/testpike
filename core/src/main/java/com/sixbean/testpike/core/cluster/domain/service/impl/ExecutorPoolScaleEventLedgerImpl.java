package com.sixbean.testpike.core.cluster.domain.service.impl;

import com.sixbean.testpike.core.cluster.api.dto.ExecutorPoolScaleEvent;
import com.sixbean.testpike.core.cluster.domain.service.ExecutorPoolScaleEventLedger;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Jdbc read side of the scale-event ledger: one aggregate query per pool, no cache —
 * the autoscaler evaluates once per tick, so freshness beats throughput here and a
 * cached clock would let the cooldown window drift (R57: nothing accumulated, nothing
 * to evict).
 */
@Service
public class ExecutorPoolScaleEventLedgerImpl implements ExecutorPoolScaleEventLedger {

    private static final String LATEST_EPOCH_SQL = """
            SELECT COALESCE(EXTRACT(epoch FROM MAX(created_at))::bigint, 0)
            FROM tp_cluster_scale_event
            WHERE pool_key = :poolKey
            """;

    private static final String RECENT_EVENTS_SQL = """
            SELECT pool_key, action, desired_replicas, current_replicas, reason,
                   EXTRACT(epoch FROM created_at)::bigint AS created_epoch_seconds
            FROM tp_cluster_scale_event
            WHERE CAST(:poolKey AS VARCHAR) IS NULL OR pool_key = CAST(:poolKey AS VARCHAR)
            ORDER BY created_at DESC
            LIMIT :limit
            """;

    /** Safety cap for one timeline read; callers ask smaller windows, the table is unbounded. */
    private static final int MAX_EVENT_WINDOW = 200;

    private final NamedParameterJdbcTemplate namedJdbcTemplate;

    public ExecutorPoolScaleEventLedgerImpl(NamedParameterJdbcTemplate namedJdbcTemplate) {
        this.namedJdbcTemplate = namedJdbcTemplate;
    }

    @Override
    public long latestScaleEpochSeconds(String poolKey) {
        Long epoch = namedJdbcTemplate.queryForObject(LATEST_EPOCH_SQL,
                new MapSqlParameterSource("poolKey", poolKey), Long.class);
        return epoch == null ? 0L : epoch;
    }

    @Override
    public List<ExecutorPoolScaleEvent> recentEvents(String poolKey, int limit) {
        int size = Math.max(1, Math.min(limit, MAX_EVENT_WINDOW));
        String filter = poolKey == null || poolKey.isBlank() ? null : poolKey;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("poolKey", filter)
                .addValue("limit", size);
        return namedJdbcTemplate.query(RECENT_EVENTS_SQL, params, (rs, rowNum) -> new ExecutorPoolScaleEvent(
                rs.getString("pool_key"),
                rs.getString("action"),
                rs.getInt("desired_replicas"),
                rs.getInt("current_replicas"),
                rs.getString("reason"),
                rs.getLong("created_epoch_seconds")));
    }
}
