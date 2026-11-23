package com.sixbean.testpike.core.mock.domain.service.impl;

import com.sixbean.testpike.core.common.domain.component.SortColumnPolicy;
import com.sixbean.testpike.core.mock.api.dto.MockServingEndpointPageView;
import com.sixbean.testpike.core.mock.api.dto.MockServingEndpointView;
import com.sixbean.testpike.core.mock.domain.component.PageWindow;
import com.sixbean.testpike.core.mock.domain.service.MockServingInventoryService;
import com.sixbean.testpike.core.mock.infrastructure.dataobject.MockServingEndpointDO;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Serving inventory implementation — one SQL over the single rule source
 * (tp_mock_stub JOIN tp_mock_space, hit ledger aggregated per stub over a
 * bounded 7-day window, LEFT JOIN so never-hit rules honestly show 0 instead
 * of vanishing). No N+1: the hit counts ride in a pre-aggregated subquery.
 * <p>Honesty rules baked in:
 * <ul>
 * <li>disabled rules ARE listed (an operator auditing "what exists" needs
 * them); the enabled switch tells them apart at a glance and the row never
 * claims a hit rate of its own beyond the ledger fact;</li>
 * <li>draft/retired spaces are listed with their status — hiding them would
 * make the inventory read like the whole truth of the project; only the
 * status filter can take them out, explicitly;</li>
 * <li>the 7-day window is a fixed, documented horizon (same retention
 * posture as mock.hit-retention-days defaulting to 30) — older traffic
 * truthfully reads as 0, the page words it as "last 7 days", never "total".</li>
 * </ul>
 */
@Service
public class MockServingInventoryServiceImpl implements MockServingInventoryService {

    /** Hit window in days carried per row (documented, fixed — not a knob). */
    private static final int HIT_WINDOW_DAYS = 7;

    private static final BeanPropertyRowMapper<MockServingEndpointDO> MAPPER =
            new BeanPropertyRowMapper<>(MockServingEndpointDO.class);

    private final NamedParameterJdbcTemplate jdbc;

    public MockServingInventoryServiceImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public MockServingEndpointPageView listProjectEndpoints(long projectId, int page, int pageSize,
                                                            Integer status, String method,
                                                            String keyword) {
        // Historical callers keep the routing-first default order untouched.
        return listProjectEndpoints(projectId, page, pageSize, status, method, keyword, null, null);
    }

    /**
     * Column keys the operator may sort by -> physical columns (or projected
     * aliases). Values are never user input: injection-shaped keys are refused
     * by the shared fail-closed gate before any SQL is built.
     */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "space_name", "sp.name",
            "space_status", "sp.status",
            "path_pattern", "st.path_pattern",
            "method", "st.method",
            "hits_7d", "hits7d",
            "updated_at", "st.updated_at");

    @Override
    public MockServingEndpointPageView listProjectEndpoints(long projectId, int page, int pageSize,
                                                            Integer status, String method,
                                                            String keyword,
                                                            String sortBy, String sortDir) {
        PageWindow window = PageWindow.of(page, pageSize);
        MapSqlParameterSource params = new MapSqlParameterSource("pid", projectId)
                .addValue("days", HIT_WINDOW_DAYS);
        StringBuilder filters = new StringBuilder();
        if (status != null) {
            filters.append(" AND sp.status = :status");
            params.addValue("status", status);
        }
        if (method != null && !method.isBlank()) {
            filters.append(" AND st.method = :method");
            params.addValue("method", method.trim().toUpperCase());
        }
        if (keyword != null && !keyword.isBlank()) {
            // one fuzzy handle over the three names an operator types
            filters.append(" AND (st.path_pattern ILIKE :kw OR st.name ILIKE :kw"
                    + " OR sp.name ILIKE :kw)");
            params.addValue("kw", "%" + keyword.trim() + "%");
        }
        String where = "FROM tp_mock_stub st"
                + " JOIN tp_mock_space sp ON sp.id = st.space_id"
                + " LEFT JOIN ("
                + "   SELECT stub_id, COUNT(*) AS hits7d"
                + "     FROM tp_mock_hit_log"
                + "    WHERE matched = TRUE AND hit_at >= now() - make_interval(days => :days)"
                + "    GROUP BY stub_id"
                + " ) h ON h.stub_id = st.id"
                + " WHERE sp.project_id = :pid" + filters;
        Long total = jdbc.queryForObject("SELECT COUNT(*) " + where, params, Long.class);
        // camelCase aliases map onto the DO's bean properties through
        // BeanPropertyRowMapper (Postgres lowercases unquoted labels; the
        // mapper's underscore-insensitive match handles the rest).
        List<MockServingEndpointDO> windowRows = jdbc.query("""
                SELECT st.id                          AS stubId,
                       sp.id                          AS spaceId,
                       sp.name                        AS spaceName,
                       sp.space_key                   AS spaceKey,
                       sp.status                      AS spaceStatus,
                       sp.base_path_mode              AS basePathMode,
                       st.name                        AS ruleName,
                       st.priority                    AS priority,
                       st.method                      AS method,
                       st.path_match_type             AS pathMatchType,
                       st.path_pattern                AS pathPattern,
                       st.matchers_json::text         AS matchersJson,
                       st.response_json::text         AS responseJson,
                       st.scenario_name               AS scenarioName,
                       st.required_state              AS requiredState,
                       st.new_state                   AS newState,
                       st.source                      AS source,
                       st.source_ref                  AS sourceRef,
                       st.enabled                     AS enabled,
                       st.version                     AS version,
                       st.updated_at                  AS updatedAt,
                       COALESCE(h.hits7d, 0)          AS hits7d
                """ + " " + where
                + orderClause(sortBy, sortDir)
                + " LIMIT :limit OFFSET :offset",
                params.addValue("limit", window.size()).addValue("offset", window.offset()),
                MAPPER);
        List<MockServingEndpointView> rows = windowRows.stream()
                .map(MockServingInventoryServiceImpl::toView)
                .toList();
        return new MockServingEndpointPageView(rows, total == null ? 0L : total,
                window.page(), window.size());
    }

    /**
     * ORDER BY builder: null sort key keeps the historical routing-first
     * window (space priority, then within-space evaluation order); a whitelisted
     * key orders by that column with the stub id as tie-breaker so paging stays
     * deterministic. The key itself passes the shared fail-closed gate — only
     * map VALUES (physical columns/aliases) ever reach the SQL string.
     */
    private static String orderClause(String sortBy, String sortDir) {
        String column = SortColumnPolicy.require(sortBy, SORT_COLUMNS, "Mock接口清单");
        if (column == null) {
            return " ORDER BY sp.priority, sp.id, st.priority, st.id";
        }
        return " ORDER BY " + column
                + ("asc".equalsIgnoreCase(sortDir) ? " ASC" : " DESC")
                + ", st.id ASC";
    }

    private static MockServingEndpointView toView(MockServingEndpointDO row) {
        return new MockServingEndpointView(row.getStubId(), row.getSpaceId(), row.getSpaceName(),
                row.getSpaceKey(), row.getSpaceStatus(), row.getBasePathMode(), row.getRuleName(),
                row.getPriority(), row.getMethod(), row.getPathMatchType(), row.getPathPattern(),
                row.getMatchersJson(), row.getResponseJson(), row.getScenarioName(),
                row.getRequiredState(), row.getNewState(), row.getSource(), row.getSourceRef(),
                row.getEnabled(), row.getVersion(), row.getUpdatedAt(), row.getHits7d());
    }
}
