package com.sixbean.testpike.core.webelement.domain.service.impl;

import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.webelement.api.dto.RevisionStormView;
import com.sixbean.testpike.core.webelement.api.dto.WebElementHealthView;
import com.sixbean.testpike.core.webelement.domain.service.WebElementHealthService;
import com.sixbean.testpike.core.webelement.domain.service.WebElementStormService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Revision-storm aggregation (WE S7 A7, V610 ledger). The stale side reads
 * the stale-event rows written by the state-change choke point inside the
 * rolling window; the early-warning side reuses the step-health account's
 * about-to-break verdict — one ladder, one source, no second judgement.
 * <p>A page storms when EITHER signal alone reaches element.storm.entry-min
 * (seed 3); the two counts are reported side by side and never pooled, so
 * "already broke together" and "about to break together" stay distinct on
 * the shared storm card. Window length is element.storm.window-hours
 * (seed 24). All reads are pure SQL plus the in-process health call, zero
 * model calls.
 * <p>Lifetime: every append prunes this project's events older than
 * window-hours * 4 in the same statement pair (R57 — the creation point
 * carries its own bounded horizon, no scheduler involved).
 */
@Service
public class WebElementStormServiceImpl implements WebElementStormService {

    static final String KEY_WINDOW_HOURS = "element.storm.window-hours";
    static final String KEY_ENTRY_MIN = "element.storm.entry-min";
    static final int WINDOW_HOURS_DEFAULT = 24;
    static final int ENTRY_MIN_DEFAULT = 3;

    private final NamedParameterJdbcTemplate jdbc;
    private final ConfigService configService;
    private final WebElementHealthService healthService;

    public WebElementStormServiceImpl(NamedParameterJdbcTemplate jdbc,
                                      ConfigService configService,
                                      WebElementHealthService healthService) {
        this.jdbc = jdbc;
        this.configService = configService;
        this.healthService = healthService;
    }

    @Override
    public void recordStaleTransition(long projectId, String pageKey, long elementId) {
        jdbc.update("""
                INSERT INTO tp_web_element_stale_event (element_id, project_id, page_key)
                VALUES (:elementId, :projectId, :pageKey)
                """, new MapSqlParameterSource()
                .addValue("elementId", elementId)
                .addValue("projectId", projectId)
                .addValue("pageKey", pageKey));
        jdbc.update("""
                DELETE FROM tp_web_element_stale_event
                 WHERE project_id = :projectId
                   AND occurred_at < CURRENT_TIMESTAMP
                       - make_interval(hours => :horizon)
                """, new MapSqlParameterSource()
                .addValue("projectId", projectId)
                .addValue("horizon", intConfig(KEY_WINDOW_HOURS, WINDOW_HOURS_DEFAULT, 1, 168) * 4));
    }

    @Override
    public List<RevisionStormView> activeStorms(long projectId) {
        int window = intConfig(KEY_WINDOW_HOURS, WINDOW_HOURS_DEFAULT, 1, 168);
        int entryMin = intConfig(KEY_ENTRY_MIN, ENTRY_MIN_DEFAULT, 2, 50);
        Map<String, List<Long>> staleByPage = staleGroups(projectId, window);
        Map<String, List<Long>> previewByPage = previewGroups(projectId);
        List<RevisionStormView> storms = new ArrayList<>();
        for (String pageKey : unionOf(staleByPage, previewByPage)) {
            List<Long> stale = staleByPage.getOrDefault(pageKey, List.of());
            List<Long> preview = previewByPage.getOrDefault(pageKey, List.of());
            if (stale.size() >= entryMin || preview.size() >= entryMin) {
                storms.add(new RevisionStormView(pageKey, stale, preview));
            }
        }
        return storms;
    }

    /** Distinct elements per page that went stale inside the window. */
    private Map<String, List<Long>> staleGroups(long projectId, int windowHours) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT page_key, element_id
                  FROM tp_web_element_stale_event
                 WHERE project_id = :projectId
                   AND occurred_at > CURRENT_TIMESTAMP - make_interval(hours => :window)
                 GROUP BY page_key, element_id
                 ORDER BY page_key, element_id
                """, new MapSqlParameterSource()
                .addValue("projectId", projectId)
                .addValue("window", windowHours));
        Map<String, List<Long>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            groups.computeIfAbsent((String) row.get("page_key"), k -> new ArrayList<>())
                    .add(((Number) row.get("element_id")).longValue());
        }
        return groups;
    }

    /**
     * Confirmed/stale elements the health account currently marks about to
     * break, grouped per page. Elements without any anchor outcome inside
     * the health windows read unreached and never join (silent pages must
     * not look sick — same posture as the health column).
     */
    private record TrackedElement(long id, String pageKey) {
    }

    private Map<String, List<Long>> previewGroups(long projectId) {
        List<TrackedElement> tracked = jdbc.query(
                "SELECT id, page_key FROM tp_web_element"
                        + " WHERE project_id = :projectId AND state IN (1, 2) ORDER BY id",
                new MapSqlParameterSource("projectId", projectId),
                (rs, n) -> new TrackedElement(rs.getLong("id"), rs.getString("page_key")));
        if (tracked.isEmpty()) {
            return Map.of();
        }
        Map<Long, WebElementHealthView> health = healthService.healthFor(
                tracked.stream().map(TrackedElement::id).toList());
        Map<String, List<Long>> groups = new LinkedHashMap<>();
        for (TrackedElement element : tracked) {
            WebElementHealthView view = health.get(element.id());
            if (view != null && view.reached() && view.aboutToBreak()) {
                groups.computeIfAbsent(element.pageKey(), k -> new ArrayList<>())
                        .add(element.id());
            }
        }
        return groups;
    }

    private static List<String> unionOf(Map<String, List<Long>> a, Map<String, List<Long>> b) {
        java.util.TreeSet<String> keys = new java.util.TreeSet<>(a.keySet());
        keys.addAll(b.keySet());
        return List.copyOf(keys);
    }

    /** Hot-key read with clamp + non-numeric fallback (health-service precedent). */
    private int intConfig(String key, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(
                    configService.getValue(key, String.valueOf(fallback)).trim());
            return Math.min(max, Math.max(min, value));
        } catch (RuntimeException unusable) {
            return fallback;
        }
    }
}
