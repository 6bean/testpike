package com.sixbean.testpike.core.linkage.domain.component;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Single source binding bus member tokens (
 * {@link com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventType})
 * to the physical tables holding those objects. The orphan-edge scan and any
 * future edge consumer read the live database through this map instead of
 * each keeping its own copy — two tables of the same fact drift apart the
 * moment one side renames a column, and the drift would silently empty the
 * edge ledger.
 * <p>Boundary: only upstream mutable members are bound here. A downstream
 * table is covered by an edge row naming it (the table name, the name
 * without its {@code tp_} prefix, or another bound token resolving to the
 * same table), so registering a new downstream edge never requires touching
 * this class.
 */
public final class LinkageObjectTables {

    /** Bus member token -&gt; physical table holding the object rows. */
    private static final Map<String, String> TOKEN_TO_TABLE;

    static {
        Map<String, String> bound = new TreeMap<>();
        bound.put("case", "tp_test_case");
        bound.put("requirement", "tp_requirement");
        bound.put("api_spec", "tp_api_spec");
        bound.put("mock", "tp_mock_stub");
        bound.put("doc", "tp_document");
        bound.put("perf_scenario", "tp_perf_scenario");
        bound.put("plan", "tp_exec_plan");
        bound.put("prompt_template", "tp_prompt_template");
        bound.put("tag", "tp_tag_dict");
        bound.put("skill", "tp_skill");
        TOKEN_TO_TABLE = Collections.unmodifiableMap(bound);
    }

    private LinkageObjectTables() {
        // static lookup only: one map, no instances
    }

    /** Physical table for a bus member token, empty when nothing is bound. */
    public static Optional<String> tableOf(String token) {
        if (token == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(TOKEN_TO_TABLE.get(token.trim()));
    }

    /** True when this token has a bound physical table. */
    public static boolean isBound(String token) {
        return token != null && TOKEN_TO_TABLE.containsKey(token.trim());
    }

    /** Bus member tokens bound here, sorted for stable reporting. */
    public static Set<String> tokens() {
        return TOKEN_TO_TABLE.keySet();
    }

    /** All bound physical tables, sorted for stable reporting. */
    public static Collection<String> tableNames() {
        return TOKEN_TO_TABLE.values();
    }

    /** Physical table that holds the given object, or the name itself when unbound. */
    public static String resolve(String objectName) {
        if (objectName == null) {
            return null;
        }
        String trimmed = objectName.trim();
        return TOKEN_TO_TABLE.getOrDefault(trimmed, trimmed);
    }

    /** Token whose table is named {@code tableName}, empty for unbound tables. */
    public static Optional<String> tokenForTable(String tableName) {
        if (tableName == null) {
            return Optional.empty();
        }
        String lower = tableName.trim().toLowerCase();
        return TOKEN_TO_TABLE.entrySet().stream()
                .filter(e -> e.getValue().equals(lower))
                .map(Map.Entry::getKey)
                .findFirst();
    }
}
