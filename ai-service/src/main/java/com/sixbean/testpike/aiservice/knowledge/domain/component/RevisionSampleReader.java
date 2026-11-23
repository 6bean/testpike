package com.sixbean.testpike.aiservice.knowledge.domain.component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Project-slice reader over the few-shot revision sample pool
 * {@code tp_ai_fewshot_revision} .
 * <p>The write side lives core-side in
 * {@code com.sixbean.testpike.core.behavior.domain.component.RevisionSamplePool};
 * this is its only consumption reader — the style few-shot bridge must be the
 * single consumer (one injection channel, one switch, no second reader
 * elsewhere, R40). NamedParameterJdbcTemplate base path per the same-domain
 * precedent {@link DocumentUsageSwitchReader}.
 * <p>Failure posture mirrors the pool's own best-effort contract: an
 * unreachable table degrades to an empty list with a WARN — the generation
 * chain is never blocked by observation material.
 */
@Component
public class RevisionSampleReader {

    private static final Logger log = LoggerFactory.getLogger(RevisionSampleReader.class);

    private final NamedParameterJdbcTemplate jdbc;

    public RevisionSampleReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Newest first {@code limit} revision samples of one project. Empty when
     * the project has no samples or the pool is unreachable.
     */
    public List<RevisionSample> recent(long projectId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT outcome, ai_text, human_text FROM tp_ai_fewshot_revision"
                            + " WHERE project_id = :projectId"
                            + " ORDER BY id DESC LIMIT :limit",
                    new MapSqlParameterSource()
                            .addValue("projectId", projectId)
                            .addValue("limit", limit));
            List<RevisionSample> samples = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                samples.add(new RevisionSample(
                        (String) row.get("outcome"),
                        (String) row.get("ai_text"),
                        (String) row.get("human_text")));
            }
            return samples;
        } catch (RuntimeException e) {
            // Pure observation material: pool unavailability never blocks generation.
            log.warn("few-shot revision pool read degraded (segment built without it)"
                    + " projectId={}: {}", projectId, e.getMessage());
            return List.of();
        }
    }
}
