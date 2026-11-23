package com.sixbean.testpike.core.webelement.domain.component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.executor.api.dto.ExecResultInput;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskInfo;
import com.sixbean.testpike.core.webelement.api.dto.WebElementAnchorHitInput;
import com.sixbean.testpike.core.webelement.domain.service.WebElementHealthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Anchor-feedback writeback for the Page Element Library (WE . The
 * executor only records what happened during its ordered multi-anchor tries
 * (03 protocol 2.4 trailing key elementAnchorHits); this component is the
 * single place that turns those raw rows into ledger knowledge (red line 6 —
 * the executor never self-scores):
 * ① aggregate deltas per (element, viewport, anchor type) and UPSERT them
 * into tp_web_element_anchor_stat (V550) — the columnar split keeps a
 * desktop hit from inflating a broken mobile anchor (design §8.2);
 * ② recompute tp_web_element.confidence from the element's full stat sum as
 * the smoothed hit rate (hits + w/2) / (hits + misses + w); the prior mean
 * 0.5 equals the neutral starting confidence, so young rows without
 * feedback keep their 0.5;
 * ③ roll the same sums into the element-level hit_count / miss_count and
 * refresh last_hit_at / last_miss_at for the outcome kinds this relay
 * actually contributed (the ledger list reads those columns, feeding them
 * from a second source would drift).
 * <p>Weight w is the hot key element.confidence-smooth-prior-weight (seed 4,
 * domain 0-1000, clamp + non-numeric fallback here — never hardcoded elsewhere).
 * <p>Hook contract (same discipline as SessionAuthRejectionWriteback): called
 * at the tail of ExecTaskServiceImpl.submitResult after the terminal UPDATE,
 * in the same transaction; submitResult's idempotent early return on
 * non-RUNNING tasks protects the hook from duplicate rewrites, and any failure
 * here is logged and swallowed — a writeback problem must never break the
 * result relay chain. R57: stat rows are bounded by the element FK
 * (ON DELETE CASCADE), so retiring an element drops its stats.
 */
@Component
public class WebElementAnchorStatWriteback {

    private static final Logger log = LoggerFactory.getLogger(WebElementAnchorStatWriteback.class);

    /** Hot smoothing prior (tp_sys_config); seed 4, accepted domain 0-1000. */
    static final String PRIOR_WEIGHT_KEY = "element.confidence-smooth-prior-weight";
    static final int PRIOR_WEIGHT_DEFAULT = 4;
    static final int PRIOR_WEIGHT_MIN = 0;
    static final int PRIOR_WEIGHT_MAX = 1000;
    /** Executor sentinel naming a clipped report — never an anchor outcome. */
    static final String TRUNCATION_SENTINEL = "__truncated__";

    private static final String UPSERT_SQL = """
            INSERT INTO tp_web_element_anchor_stat
                    (element_id, viewport, anchor_type, hits, misses, updated_at)
            VALUES (:elementId, :viewport, :anchorType, :hits, :misses, now())
            ON CONFLICT (element_id, viewport, anchor_type)
            DO UPDATE SET hits = tp_web_element_anchor_stat.hits + EXCLUDED.hits,
                          misses = tp_web_element_anchor_stat.misses + EXCLUDED.misses,
                          updated_at = now()
            """;

    /** Health-plane rollup: today's row per (element, viewport), V609. */
    private static final String DAILY_UPSERT_SQL = """
            INSERT INTO tp_web_element_health_daily
                    (bucket_date, element_id, viewport, hits, misses, updated_at)
            VALUES (CURRENT_DATE, :elementId, :viewport, :hits, :misses, now())
            ON CONFLICT (bucket_date, element_id, viewport)
            DO UPDATE SET hits = tp_web_element_health_daily.hits + EXCLUDED.hits,
                          misses = tp_web_element_health_daily.misses + EXCLUDED.misses,
                          updated_at = now()
            """;

    private static final String DAILY_PRUNE_SQL = """
            DELETE FROM tp_web_element_health_daily
             WHERE bucket_date < CURRENT_DATE - :horizon
            """;

    private final ObjectMapper objectMapper;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;
    private final ConfigService configService;
    private final WebElementHealthService healthService;

    public WebElementAnchorStatWriteback(ObjectMapper objectMapper,
                                         NamedParameterJdbcTemplate namedJdbcTemplate,
                                         ConfigService configService,
                                         WebElementHealthService healthService) {
        this.objectMapper = objectMapper;
        this.namedJdbcTemplate = namedJdbcTemplate;
        this.configService = configService;
        this.healthService = healthService;
    }

    /** Result-relay tail hook: see class contract. Never throws. */
    public void onResultSubmitted(ExecTaskInfo task, ExecResultInput input) {
        if (task.taskType() != 1) {
            return;
        }
        // Trailing-key precedent: absent key = legacy relay, zero drift.
        if (input.elementAnchorHits() == null) {
            return;
        }
        try {
            write(task, input);
        } catch (RuntimeException hookFailure) {
            log.warn("element anchor stat writeback skipped for task {}: {}",
                    task.id(), hookFailure.getMessage());
        }
    }

    private void write(ExecTaskInfo task, ExecResultInput input) {
        Map<WebElementAnchorStatKey, long[]> deltas = aggregate(input.elementAnchorHits());
        if (deltas.isEmpty()) {
            return;
        }
        Set<Long> liveElements = existingElements(deltas.keySet());
        List<MapSqlParameterSource> batch = new ArrayList<>();
        Map<Long, long[]> perElementTotals = new HashMap<>();
        Map<Map.Entry<Long, String>, long[]> dailyTotals = new LinkedHashMap<>();
        for (Map.Entry<WebElementAnchorStatKey, long[]> entry : deltas.entrySet()) {
            WebElementAnchorStatKey key = entry.getKey();
            if (!liveElements.contains(key.elementId())) {
                // Ledger row retired mid-flight: nothing to write back to —
                // honest skip, same shape as the auth-rejection hook.
                continue;
            }
            batch.add(new MapSqlParameterSource()
                    .addValue("elementId", key.elementId())
                    .addValue("viewport", key.viewport())
                    .addValue("anchorType", key.anchorType())
                    .addValue("hits", entry.getValue()[0])
                    .addValue("misses", entry.getValue()[1]));
            long[] totals = perElementTotals.computeIfAbsent(key.elementId(), id -> new long[4]);
            totals[0] += entry.getValue()[0];
            totals[1] += entry.getValue()[1];
            long[] daily = dailyTotals.computeIfAbsent(
                    Map.entry(key.elementId(), key.viewport()), k -> new long[2]);
            daily[0] += entry.getValue()[0];
            daily[1] += entry.getValue()[1];
        }
        if (batch.isEmpty()) {
            return;
        }
        namedJdbcTemplate.batchUpdate(UPSERT_SQL, batch.toArray(MapSqlParameterSource[]::new));
        int weight = priorWeight();
        perElementTotals.forEach((elementId, touched) ->
                recomputeElement(elementId, weight, touched[0] > 0, touched[1] > 0));
        rollDaily(dailyTotals);
        perElementTotals.keySet().forEach(healthService::evaluateDrift);
    }

    /**
     * Health-plane rollup (WE S7 8.3): merge today's per-(element, viewport)
     * totals (live elements only, anchor types already summed) into the
     * daily table and prune rows past the retention horizon in the same
     * step — the creation point owns its own cleanup (R57). Drift alerts
     * ride the touched elements; the day-window dedup keeps the bell quiet.
     */
    private void rollDaily(Map<Map.Entry<Long, String>, long[]> dailyTotals) {
        if (dailyTotals.isEmpty()) {
            return;
        }
        List<MapSqlParameterSource> batch = new ArrayList<>();
        dailyTotals.forEach((entry, totals) -> batch.add(new MapSqlParameterSource()
                .addValue("elementId", entry.getKey())
                .addValue("viewport", entry.getValue())
                .addValue("hits", totals[0])
                .addValue("misses", totals[1])));
        namedJdbcTemplate.batchUpdate(DAILY_UPSERT_SQL, batch.toArray(MapSqlParameterSource[]::new));
        namedJdbcTemplate.update(DAILY_PRUNE_SQL, new MapSqlParameterSource(
                "horizon", healthService.retentionDays()));
    }

    /**
 * Filter (executor records, core scores): only attempted outcomes on an
 * addressable anchor of an identified element count. Untried rows, the
 * truncation sentinel, blank viewport/type and missing element ids are
 * dropped; no anchor-type whitelist here (the bilateral key gate already
 * pins the vocabulary, and a second copy would drift against 's
 * role/label extension).
 */
    private Map<WebElementAnchorStatKey, long[]> aggregate(Object raw) {
        Map<WebElementAnchorStatKey, long[]> deltas = new LinkedHashMap<>();
        for (WebElementAnchorHitInput row : convertRows(raw)) {
            if (row.elementId() == null || !Boolean.TRUE.equals(row.tried())) {
                continue;
            }
            String viewport = trimToNull(row.viewport());
            String anchorType = trimToNull(row.anchorType());
            if (viewport == null || anchorType == null
                    || TRUNCATION_SENTINEL.equals(anchorType)) {
                continue;
            }
            WebElementAnchorStatKey key =
                    new WebElementAnchorStatKey(row.elementId(), viewport, anchorType);
            long[] counters = deltas.computeIfAbsent(key, k -> new long[2]);
            if (Boolean.TRUE.equals(row.hit())) {
                counters[0]++;
            } else {
                counters[1]++;
            }
        }
        return deltas;
    }

    private List<WebElementAnchorHitInput> convertRows(Object raw) {
        try {
            if (raw instanceof String json && !json.isBlank()) {
                return objectMapper.readValue(json, new TypeReference<>() {
                });
            }
            return objectMapper.convertValue(raw, new TypeReference<>() {
            });
        } catch (Exception malformed) {
            // Un-parseable evidence plane: skip the writeback, keep the relay.
            return List.of();
        }
    }

    private Set<Long> existingElements(Set<WebElementAnchorStatKey> keys) {
        Set<Long> ids = new HashSet<>();
        keys.forEach(key -> ids.add(key.elementId()));
        return new HashSet<>(namedJdbcTemplate.queryForList(
                "SELECT id FROM tp_web_element WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", ids), Long.class));
    }

    /** One read-modify-write of the element's knowledge, summed columnar-wide. */
    private void recomputeElement(long elementId, int weight,
                                  boolean touchedHit, boolean touchedMiss) {
        Map<String, Object> sums = namedJdbcTemplate.queryForMap(
                "SELECT COALESCE(SUM(hits), 0) AS h, COALESCE(SUM(misses), 0) AS m "
                        + "FROM tp_web_element_anchor_stat WHERE element_id = :id",
                new MapSqlParameterSource("id", elementId));
        long hits = ((Number) sums.get("h")).longValue();
        long misses = ((Number) sums.get("m")).longValue();
        namedJdbcTemplate.update("""
                UPDATE tp_web_element
                   SET confidence = :confidence,
                       hit_count = :hits,
                       miss_count = :misses,
                       last_hit_at = CASE WHEN :touchedHit THEN now() ELSE last_hit_at END,
                       last_miss_at = CASE WHEN :touchedMiss THEN now() ELSE last_miss_at END,
                       updated_at = now()
                 WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("confidence", smoothedConfidence(hits, misses, weight))
                        .addValue("hits", hits)
                        .addValue("misses", misses)
                        .addValue("touchedHit", touchedHit)
                        .addValue("touchedMiss", touchedMiss)
                        .addValue("id", elementId));
    }

    /** Smoothed hit rate; prior mean 0.5 keeps young rows at the neutral start. */
    static double smoothedConfidence(long hits, long misses, int weight) {
        long total = hits + misses + weight;
        if (total <= 0) {
            return 0.5;
        }
        return (hits + weight / 2.0) / total;
    }

    private int priorWeight() {
        try {
            int weight = Integer.parseInt(
                    configService.getValue(PRIOR_WEIGHT_KEY,
                            String.valueOf(PRIOR_WEIGHT_DEFAULT)).trim());
            return Math.min(PRIOR_WEIGHT_MAX, Math.max(PRIOR_WEIGHT_MIN, weight));
        } catch (RuntimeException unusable) {
            return PRIOR_WEIGHT_DEFAULT;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
