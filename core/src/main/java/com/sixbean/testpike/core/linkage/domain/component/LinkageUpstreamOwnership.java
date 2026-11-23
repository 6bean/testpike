package com.sixbean.testpike.core.linkage.domain.component;

import com.sixbean.testpike.core.linkage.api.dto.UpstreamOwnership;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Single source for "which project and creator own an upstream object"
 * . The impact ledger deliberately stores no project column
 * (one ledger, one truth), so every consumer — the panel read and the
 * urge scan — resolves ownership by reading the object row itself
 * through this component. A new bus member becomes resolvable here together
 * with its edge rows, never by trusting a caller-supplied project id.
 */
@Component
public class LinkageUpstreamOwnership {

    /** kind -> table holding the ownership columns; title column is uniform. */
    private static final Map<String, String> PATHS = Map.of(
            "case", "tp_test_case",
            "requirement", "tp_requirement");

    private final NamedParameterJdbcTemplate named;

    public LinkageUpstreamOwnership(NamedParameterJdbcTemplate named) {
        this.named = named;
    }

    /** True when this bus member has a registered ownership path. */
    public boolean knows(String kind) {
        return kind != null && PATHS.containsKey(kind);
    }

    /** Human label used inside notification copy (raw token as honest fallback). */
    public static String labelOf(String kind) {
        return switch (kind == null ? "" : kind) {
            case "case" -> "用例";
            case "requirement" -> "需求";
            default -> kind;
        };
    }

    /** Resolve project + creator + title of the upstream row; empty when unknown kind or deleted row. */
    public Optional<UpstreamOwnership> resolve(String kind, long upstreamId) {
        String table = PATHS.get(kind);
        if (table == null) {
            return Optional.empty();
        }
        String sql = "SELECT project_id, created_by, title FROM " + table + " WHERE id = :upstreamId";
        try {
            return Optional.ofNullable(named.queryForObject(sql,
                    new MapSqlParameterSource("upstreamId", upstreamId),
                    (rs, n) -> new UpstreamOwnership(
                            rs.getLong("project_id"),
                            rs.getObject("created_by", Long.class),
                            rs.getString("title"))));
        } catch (org.springframework.dao.EmptyResultDataAccessException gone) {
            return Optional.empty();
        }
    }
}
