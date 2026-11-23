package com.sixbean.testpike.core.spec.domain.component;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Contract-derived mock stub lookup (single source, used by both the mock-stale
 * twin badge and the inventory Mock column — no second SQL copy).
 * <p>Scope = openapi-sourced rules in this project's mock spaces; keys are
 * folded match keys via ContractMatchKeyRules so a template stub
 * (path_pattern with {petId}) aligns with contract endpoints and hand-written
 * concrete requests alike.
 */
@Component
public class DerivedMockKeyLookup {

    private static final String SELECT = """
            SELECT st.id, st.method, st.path_pattern,
                   bool_or(h.source = 'llm') AS has_llm,
                   bool_or(h.source IS NOT NULL AND h.source <> 'llm') AS has_real
              FROM tp_mock_stub st
              JOIN tp_mock_space sp ON st.space_id = sp.id
              LEFT JOIN tp_mock_hit_log h ON h.stub_id = st.id
             WHERE sp.project_id = :projectId AND st.source = 'openapi'
             GROUP BY st.id, st.method, st.path_pattern
            """;

    private final NamedParameterJdbcTemplate namedJdbc;
    private final ContractMatchKeyRules matchKeyRules;

    public DerivedMockKeyLookup(NamedParameterJdbcTemplate namedJdbc,
                                ContractMatchKeyRules matchKeyRules) {
        this.namedJdbc = namedJdbc;
        this.matchKeyRules = matchKeyRules;
    }

    /**
     * Folded endpoint key → provenance verdict for the project's derived stubs.
     * Several stubs sharing one key merge: the key is llmOnly only when at
     * least one llm hit is recorded and no non-llm hit exists anywhere on it.
     */
    public Map<String, MockProvenance> lookup(long projectId) {
        Map<String, boolean[]> merged = new LinkedHashMap<>();
        namedJdbc.query(SELECT, new MapSqlParameterSource("projectId", projectId), rs -> {
            String key = matchKeyRules.matchKey(rs.getString(2), rs.getString(3));
            boolean[] acc = merged.computeIfAbsent(key, k -> new boolean[2]);
            acc[0] |= rs.getBoolean("has_llm");
            acc[1] |= rs.getBoolean("has_real");
        });
        Map<String, MockProvenance> out = new LinkedHashMap<>();
        merged.forEach((key, acc) -> out.put(key, new MockProvenance(acc[0] && !acc[1])));
        return out;
    }

    /**
     * Folded endpoint key → ids of the project's contract-derived stubs sharing it.
     * Third consumer of the same SELECT (mock-stale badge, inventory join, change-impact
     * closure) — a second copy of the SQL is forbidden.
     */
    public Map<String, List<Long>> stubIdsByKey(long projectId) {
        Map<String, List<Long>> out = new LinkedHashMap<>();
        namedJdbc.query(SELECT, new MapSqlParameterSource("projectId", projectId), rs -> {
            String key = matchKeyRules.matchKey(rs.getString(2), rs.getString(3));
            out.computeIfAbsent(key, k -> new ArrayList<>()).add(rs.getLong(1));
        });
        return out;
    }

    /** Folded keys only — the twin badge needs the set, not the verdicts. */
    public java.util.Set<String> keys(long projectId) {
        return lookup(projectId).keySet();
    }
}
