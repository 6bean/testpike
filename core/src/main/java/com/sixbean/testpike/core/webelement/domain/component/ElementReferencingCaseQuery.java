package com.sixbean.testpike.core.webelement.domain.component;

import com.sixbean.testpike.core.webelement.api.dto.ReferencingCasePage;
import com.sixbean.testpike.core.webelement.api.dto.ReferencingCaseView;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Read door for "cases that reference this element" on the element ledger
 * detail drawer. It consumes the existing step anchor ledger
 * (tp_case_step.anchor, dialects {kind:elem, ref:name} and
 * {kind:elem, refs:[name,...]} produced by the AI landing, manual edit and
 * script write-back chains through the single-source normalizer) — no second
 * index table and no recomputed copy, so the reference list can never drift
 * from the anchor evidence itself.
 * <p>segment posture: the result is limited to the element's own project and to
 * live rows — soft-deleted cases and step rows of stale (non-current) case
 * versions are excluded, because the drawer answers "what would break if this
 * element changed", which is a question about the current revision.
 * <p>Name-union semantics: the anchor ledger stores element names only (the
 * rename chain owns the name↔page mapping), so same-named elements on
 * different pages share one honest list; the door never filters by page on
 * its own.
 * <p>Bound posture (worst-case-scale rule): page size is clamped server-side
 * (max 100, illegal inputs degrade to 20) and the total is exact, so opening
 * one drawer never scans the whole step table into memory.
 */
@Component
public class ElementReferencingCaseQuery {

    /** Hard ceiling for one element-page request (list-visibility rule). */
    static final int MAX_PAGE_SIZE = 100;
    /** Default page size when the caller asks for something illegal. */
    static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * Shared predicate over the anchor ledger. jsonb_exists() stands in for
     * the "?" operator (the JDBC driver reserves positional placeholders),
     * the same posture the chat-image ledger already uses. The typeof guard
     * keeps a hand-written scalar "refs" from erroring the whole query.
     */
    private static final String REFERENCE_PREDICATE = """
            AND s.anchor ->> 'kind' = 'elem'
              AND ( s.anchor ->> 'ref' = :elementName
                    OR ( jsonb_typeof(s.anchor -> 'refs') = 'array'
                         AND jsonb_exists(s.anchor -> 'refs', :elementName) ) )
            """;

    private static final RowMapper<ReferencingCaseView> ROW_MAPPER = (rs, rowNum) ->
            new ReferencingCaseView(
                    rs.getLong("case_id"),
                    rs.getString("case_no"),
                    rs.getString("case_title"),
                    rs.getInt("case_version"),
                    rs.getInt("step_no"),
                    rs.getString("step_description"));

    private final NamedParameterJdbcTemplate namedJdbcTemplate;

    public ElementReferencingCaseQuery(NamedParameterJdbcTemplate namedJdbcTemplate) {
        this.namedJdbcTemplate = namedJdbcTemplate;
    }

    /**
     * Live case steps of the element's project that name this element in
     * their anchor evidence, newest case first, step order within a case.
     * An unknown element id answers an empty page (null name/project), never
     * an error — the drawer just shows nothing to reference.
     */
    public ReferencingCasePage byElement(long elementId, int page, int pageSize) {
        List<Map<String, Object>> keys = namedJdbcTemplate.queryForList(
                "SELECT name, project_id FROM tp_web_element WHERE id = :elementId",
                new MapSqlParameterSource("elementId", elementId));
        if (keys.isEmpty()) {
            return new ReferencingCasePage(List.of(), 0, Math.max(page, 1),
                    clamp(pageSize), null, null);
        }
        String elementName = (String) keys.get(0).get("name");
        long elementProjectId = ((Number) keys.get(0).get("project_id")).longValue();
        int safePage = Math.max(page, 1);
        int safeSize = clamp(pageSize);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("elementName", elementName)
                .addValue("projectId", elementProjectId);
        Long total = namedJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_case_step s JOIN tp_test_case c ON c.id = s.case_id "
                        + "WHERE c.project_id = :projectId AND c.deleted = 0 "
                        + "AND c.version = s.case_version " + REFERENCE_PREDICATE,
                params, Long.class);
        List<ReferencingCaseView> items = namedJdbcTemplate.query(
                """
                SELECT c.id AS case_id, c.case_no, c.title AS case_title,
                       c.version AS case_version, s.step_no, s.description AS step_description
                FROM tp_case_step s
                JOIN tp_test_case c ON c.id = s.case_id
                WHERE c.project_id = :projectId AND c.deleted = 0
                  AND c.version = s.case_version
                """ + REFERENCE_PREDICATE
                        + """
                ORDER BY s.case_id DESC, s.step_no ASC
                LIMIT :limit OFFSET :offset
                """,
                params.addValue("limit", safeSize)
                        .addValue("offset", (long) (safePage - 1) * safeSize),
                ROW_MAPPER);
        return new ReferencingCasePage(items, total == null ? 0L : total,
                safePage, safeSize, elementName, elementProjectId);
    }

    private static int clamp(int pageSize) {
        return pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
    }
}
