package com.sixbean.testpike.core.webelement.domain.component;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Single source of the "element already lives in the page element library"
 * key set (WE-S6 census migration, piece 3/3b): ledger card posting
 * excludes such elements before filing, replacement card posting only
 * proposes for them — one query, one key shape (normalized page + element
 * name), no second copy of the membership rule (R40).
 * <p>Retired rows (state 3) deliberately stay outside the live set: a
 * retired element may be re-filed from a fresh census; stale rows (state 2)
 * likewise, because the dispatch snapshot will not resolve them and a
 * replacement against them would ship a dead reference.
 */
@Component
public class WebElementLibraryKeys {

    private final NamedParameterJdbcTemplate jdbc;

    public WebElementLibraryKeys(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Elements living in the library (candidate or confirmed), by page + name. */
    public Set<String> keysFor(long projectId) {
        Set<String> keys = new HashSet<>();
        jdbc.query("""
                SELECT page_key, name FROM tp_web_element
                WHERE project_id = :projectId AND state IN (0, 1)
                """, new MapSqlParameterSource().addValue("projectId", projectId),
                rs -> {
                    keys.add(rs.getString("page_key") + "\u0000" + rs.getString("name"));
                });
        return keys;
    }
}
