package com.sixbean.testpike.core.webelement.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.webelement.PageWriteProfilePageView;
import com.sixbean.testpike.common.webelement.PageWriteProfileView;
import com.sixbean.testpike.common.webelement.PageWriteRiskView;
import com.sixbean.testpike.core.webelement.domain.service.PageWriteProfileQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Paged reads over {@code tp_page_write_profile}, shaped after the sibling
 * run-ledger list door (bounded segment, newest first, malformed JSON windows
 * degrade to an empty list with a warning instead of throwing a read).
 * Posture stays deliberate: reads never create rows and never fail the
 * screen because one booked window stopped parsing.
 */
@Service
public class PageWriteProfileQueryServiceImpl implements PageWriteProfileQueryService {

    private static final Logger log = LoggerFactory.getLogger(PageWriteProfileQueryServiceImpl.class);

    /** Door cap for one page segment (S-group worst-scale; the screen pages, the ledger never dumps). */
    private static final int MAX_PAGE_SIZE = 200;

    /** Door cap of the narrow kernel risk read (S-group worst-scale; the prompt
     *  annotates a bounded face, the full rows stay on the paged door). */
    private static final int MAX_RISK_ROWS = 200;

    /** Column window both read doors decode through one shared mapper (R40). */
    private static final String ROW_SELECT = """
            SELECT id, project_id, page_key, page_url, write_surface::text AS write_surface,
                   write_events::text AS write_events, net_failures::text AS net_failures,
                   snapshot_only, last_run_id, first_seen_at, last_seen_at
            FROM tp_page_write_profile
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PageWriteProfileQueryServiceImpl(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public PageWriteProfilePageView pageByProject(long projectId, int page, int pageSize, String keyword) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);
        String filter = keyword == null || keyword.isBlank()
                ? "" : " AND lower(page_url) LIKE lower(:keyword)";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("projectId", projectId)
                .addValue("keyword", "%" + (keyword == null ? "" : keyword.trim()) + "%");
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM tp_page_write_profile WHERE project_id = :projectId" + filter,
                params, Long.class);
        params.addValue("limit", safeSize).addValue("offset", (safePage - 1) * safeSize);
        List<PageWriteProfileView> rows = jdbc.query(ROW_SELECT
                + " WHERE project_id = :projectId" + filter
                + " ORDER BY last_seen_at DESC, id DESC LIMIT :limit OFFSET :offset",
                params, this::mapRow);
        return new PageWriteProfilePageView(rows, total == null ? rows.size() : total, safePage, safeSize);
    }

    @Override
    public PageWriteProfileView findByIdInProject(long projectId, long profileId) {
        List<PageWriteProfileView> rows = jdbc.query(ROW_SELECT
                        + " WHERE project_id = :projectId AND id = :profileId",
                new MapSqlParameterSource().addValue("projectId", projectId).addValue("profileId", profileId),
                this::mapRow);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public List<PageWriteRiskView> riskPagesForProject(long projectId) {
        return jdbc.query(
                "SELECT page_key, page_url FROM tp_page_write_profile"
                        + " WHERE project_id = :projectId"
                        + " AND (snapshot_only"
                        + "      OR COALESCE(jsonb_array_length("
                        + "           CASE WHEN jsonb_typeof(write_surface) = 'array'"
                        + "                THEN write_surface ELSE NULL END), 0) > 0)"
                        + " ORDER BY last_seen_at DESC, id DESC LIMIT :limit",
                new MapSqlParameterSource()
                        .addValue("projectId", projectId)
                        .addValue("limit", MAX_RISK_ROWS),
                (rs, rowNum) -> new PageWriteRiskView(rs.getString("page_key"),
                        rs.getString("page_url")));
    }

    /** The one row→view decode shape shared by both read doors (no second copy). */
    private PageWriteProfileView mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new PageWriteProfileView(
                rs.getLong("id"),
                rs.getLong("project_id"),
                rs.getString("page_key"),
                rs.getString("page_url"),
                stringsOf(rs.getString("write_surface")),
                nodesOf(rs.getString("write_events")),
                nodesOf(rs.getString("net_failures")),
                rs.getBoolean("snapshot_only"),
                (Long) rs.getObject("last_run_id"),
                rs.getTimestamp("first_seen_at").toLocalDateTime(),
                rs.getTimestamp("last_seen_at").toLocalDateTime());
    }

    /** String-array window decoded for display; one decode shape, never re-implemented per column. */
    private List<String> stringsOf(String raw) {
        List<String> items = new ArrayList<>();
        nodesOf(raw).forEach(node -> items.add(node.asText()));
        return items;
    }

    /** JSON-array window decoded to wire shape; a malformed booked window degrades to empty with a WARN. */
    private List<JsonNode> nodesOf(String raw) {
        List<JsonNode> nodes = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(raw == null || raw.isBlank() ? "[]" : raw);
            root.forEach(nodes::add);
        } catch (Exception malformed) {
            log.warn("page write-profile window unreadable (rebuilt empty): {}", malformed.getMessage());
        }
        return nodes;
    }
}
