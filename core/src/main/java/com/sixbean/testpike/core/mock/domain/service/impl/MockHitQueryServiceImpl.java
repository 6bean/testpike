package com.sixbean.testpike.core.mock.domain.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.common.domain.component.SortColumnPolicy;
import com.sixbean.testpike.core.mock.api.dto.MockHitInfo;
import com.sixbean.testpike.core.mock.api.dto.MockHitPageView;
import com.sixbean.testpike.core.mock.domain.component.PageWindow;
import com.sixbean.testpike.core.mock.domain.service.MockHitQueryService;
import com.sixbean.testpike.core.mock.infrastructure.dataobject.MockHitDO;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Hit ledger read side implementation . The inspector
 * walks tp_mock_hit_log — rows only ever written by {@code MockHitLedgerService}
 * from the MQ batch — so this service is strictly read-mostly: one count plus
 * one window query per page, ordered by serve time (hit_at DESC, id DESC so
 * rows sharing one millisecond still page deterministically).
 * <p>Filter spectrum: matched=null = both, true = hits, false = misses. The
 * boolean arrives as a SQL parameter only, never concatenated — no injection
 * surface. Unknown space ids are refused BEFORE querying, so a deleted space
 * can never be answered with a fake-empty page.
 * <p>jsonb facets are parsed here (the DO keeps ::text aliases); an unreadable
 * snapshot degrades to an empty map and an unreadable trail to an empty list —
 * honest empties, never a dropped row and never a re-masking pass (the plane
 * already desensitized before publishing).
 */
@Service
public class MockHitQueryServiceImpl implements MockHitQueryService {

    private static final BeanPropertyRowMapper<MockHitDO> MAPPER =
            new BeanPropertyRowMapper<>(MockHitDO.class);

    private static final TypeReference<Map<String, Object>> SNAPSHOT_TYPE =
            new TypeReference<>() {
            };

    private static final TypeReference<List<Map<String, Object>>> TRACE_TYPE =
            new TypeReference<>() {
            };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public MockHitQueryServiceImpl(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public MockHitPageView listBySpace(long spaceId, Boolean matched, int page, int pageSize) {
        // Historical callers keep the newest-first timeline untouched.
        return listBySpace(spaceId, matched, page, pageSize, null, null);
    }

    /**
     * Column keys the inspector may sort by -> SQL expressions (physical
     * columns or the masked jsonb facets the table renders). Values are never
     * user input: off-list/injection-shaped keys are refused by the shared
     * fail-closed gate before any SQL is built.
     */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "hit_at", "hit_at",
            "method", "request_snapshot->>'method'",
            "path", "request_snapshot->>'path'",
            "matched", "matched",
            "latency_ms", "latency_ms",
            "stub_name", "request_snapshot->>'stubName'");

    @Override
    public MockHitPageView listBySpace(long spaceId, Boolean matched, int page, int pageSize,
                                       String sortBy, String sortDir) {
        requireSpace(spaceId);
        // Clamp dialect is the shared PageWindow single source .
        PageWindow window = PageWindow.of(page, pageSize);
        int safePage = window.page();
        int safeSize = window.size();

        MapSqlParameterSource countParams = new MapSqlParameterSource()
                .addValue("sid", spaceId)
                .addValue("matched", matched, Types.BOOLEAN);
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM tp_mock_hit_log
                WHERE space_id = :sid AND (:matched IS NULL OR matched = :matched)
                """, countParams, Long.class);

        List<MockHitDO> rows = jdbc.query("""
                SELECT id, space_id, stub_id, request_snapshot::text AS request_snapshot,
                       response_snapshot::text AS response_snapshot,
                       matched, match_trace::text AS match_trace, latency_ms,
                       fault_applied, session_id, source, hit_at
                FROM tp_mock_hit_log
                WHERE space_id = :sid AND (:matched IS NULL OR matched = :matched)
                """ + orderClause(sortBy, sortDir) + """
                 LIMIT :limit OFFSET :offset
                """,
                new MapSqlParameterSource()
                        .addValue("sid", spaceId)
                        .addValue("matched", matched, Types.BOOLEAN)
                        .addValue("limit", safeSize)
                        .addValue("offset", (long) (safePage - 1) * safeSize),
                MAPPER);

        List<MockHitInfo> list = rows.stream().map(this::toInfo).toList();
        return new MockHitPageView(list, total == null ? 0L : total, safePage, safeSize);
    }

    /**
     * ORDER BY builder: a null sort key keeps the historical newest-first
     * timeline (hit_at DESC, id DESC so rows sharing one millisecond still
     * page deterministically); a whitelisted key orders by that expression
     * with the row id as tie-breaker. The key passes the shared fail-closed
     * gate — only map VALUES (columns/jsonb facets) ever reach the SQL string.
     */
    private static String orderClause(String sortBy, String sortDir) {
        String column = SortColumnPolicy.require(sortBy, SORT_COLUMNS, "Mock命中流水");
        if (column == null) {
            return " ORDER BY hit_at DESC, id DESC";
        }
        return " ORDER BY " + column
                + ("asc".equalsIgnoreCase(sortDir) ? " ASC" : " DESC")
                + ", id DESC";
    }

    /**
     * Counting is the page query minus the window — same spectrum, same ghost
     * refusal, one SQL. The since bound uses the serve instant (hit_at), the
     * same column the inspector sorts on, so "hits since my run started" and
     * "the rows the inspector shows" never disagree.
     */
    @Override
    public long countBySpace(long spaceId, LocalDateTime since, Boolean matched) {
        requireSpace(spaceId);
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM tp_mock_hit_log
                WHERE space_id = :sid
                  AND (:matched IS NULL OR matched = :matched)
                  AND (CAST(:since AS timestamp) IS NULL OR hit_at >= CAST(:since AS timestamp))
                """,
                new MapSqlParameterSource()
                        .addValue("sid", spaceId)
                        .addValue("matched", matched, Types.BOOLEAN)
                        .addValue("since", since, Types.TIMESTAMP),
                Long.class);
        return count == null ? 0L : count;
    }

    /** Ghost ids get a plain-worded refusal instead of a pretend-empty ledger. */
    private void requireSpace(long spaceId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM tp_mock_space WHERE id = :id",
                new MapSqlParameterSource("id", spaceId), Integer.class);
        if (count == null || count == 0) {
            throw new ServiceException("该 Mock 空间不存在或已被删除，命中流水无从查起",
                    BizErrorCode.NOT_FOUND);
        }
    }

    private MockHitInfo toInfo(MockHitDO row) {
        return new MockHitInfo(row.getId(), row.getSpaceId(), row.getStubId(),
                parseSnapshot(row.getRequestSnapshot()), parseSnapshot(row.getResponseSnapshot()),
                row.getMatched(),
                parseTrace(row.getMatchTrace()), row.getLatencyMs(),
                row.getFaultApplied(), row.getSessionId(), row.getHitAt(), row.getSource());
    }

    private Map<String, Object> parseSnapshot(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, SNAPSHOT_TYPE);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private List<Map<String, Object>> parseTrace(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, TRACE_TYPE);
        } catch (Exception ex) {
            return List.of();
        }
    }
}
