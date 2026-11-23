package com.sixbean.testpike.core.mock.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.MockHitBatchMessage;
import com.sixbean.testpike.common.mq.MockHitRowMessage;
import com.sixbean.testpike.core.mock.domain.service.MockHitLedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hit ledger landing implementation — the ONLY writer of tp_mock_hit_log
 * , AG design D-b: the data plane never touches
 * PostgreSQL, so everything the inspector shows arrives through this door).
 * <p>Column mapping (batch → ledger): space resolved from the batch's
 * spaceKey (unknown key = reject the whole batch upward so the message
 * dead-letters visibly instead of vanishing silently), request_snapshot =
 * jsonb {method,path,query,headers,body} exactly as the plane masked it (core
 * never re-masks and never drops facets — the rows already passed the shared
 * desensitizer before leaving the plane), response_snapshot = jsonb
 * {status,headers,body} of what the mock actually answered, or SQL NULL when
 * the row carries no response plane (absent and empty must stay tellable
 * apart), match_trace = the per-matcher
 * verdict rows (corrupt JSON honestly degrades to an empty array instead of
 * losing the row), hit_at = the plane's occurredAt instant, so the inspector
 * timeline is serve time and not consume time.
 * <p>Rows insert in one batched write per message: a mocked-traffic deluge
 * must cost the database one statement, not one round trip per observation.
 */
@Service
public class MockHitLedgerServiceImpl implements MockHitLedgerService {

    private static final Logger log = LoggerFactory.getLogger(MockHitLedgerServiceImpl.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final com.sixbean.testpike.core.mock.domain.component.MockBodyTruncator truncator;

    public MockHitLedgerServiceImpl(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper,
                                    com.sixbean.testpike.core.mock.domain.component.MockBodyTruncator truncator) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.truncator = truncator;
    }

    @Override
    @Transactional
    public void landBatch(MockHitBatchMessage batch) {
        if (batch == null || batch.rows() == null || batch.rows().isEmpty()) {
            return;
        }
        long spaceId = requireSpaceId(batch.spaceKey());
        MapSqlParameterSource[] params = batch.rows().stream()
                .map(row -> new MapSqlParameterSource()
                        .addValue("spaceId", spaceId)
                        .addValue("stubId", row.stubId())
                        .addValue("snapshot", snapshotJson(row))
                        .addValue("response", responseJson(row))
                        .addValue("matched", row.matched())
                        .addValue("trace", traceJson(row))
                        .addValue("latencyMs", row.latencyMs())
                        .addValue("faultApplied", row.faultApplied())
                        .addValue("sessionId", row.sessionId())
                        .addValue("source", row.source())
                        .addValue("hitAt", new Timestamp(row.occurredAt())))
                .toArray(MapSqlParameterSource[]::new);
        int[] landed = jdbc.batchUpdate("""
                INSERT INTO tp_mock_hit_log
                    (space_id, stub_id, request_snapshot, response_snapshot, matched, match_trace,
                     latency_ms, fault_applied, session_id, source, hit_at)
                VALUES
                    (:spaceId, :stubId, CAST(:snapshot AS jsonb), CAST(:response AS jsonb), :matched,
                     CAST(:trace AS jsonb), :latencyMs, :faultApplied, :sessionId, :source, :hitAt)
                """, params);
        log.info("Mock 命中流水落账 batch={} space={} rows={}",
                batch.batchId(), batch.spaceKey(), landed.length);
    }

    /** spaceKey → space id; a key the ledger cannot attribute is a hard reject. */
    private long requireSpaceId(String spaceKey) {
        String key = spaceKey == null ? null : spaceKey.trim();
        if (key == null || key.isEmpty()) {
            throw new ServiceException("命中流水缺少空间路由标识（spaceKey）——整批拒收进死信，禁静默丢",
                    BizErrorCode.VALIDATION_ERROR);
        }
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM tp_mock_space WHERE space_key = :key",
                new MapSqlParameterSource("key", key), Long.class);
        if (ids.isEmpty()) {
            throw new ServiceException("命中流水指向不存在的路由标识「" + key
                    + "」——空间可能已被删除，整批拒收进死信留痕", BizErrorCode.NOT_FOUND);
        }
        return ids.get(0);
    }

    /**
     * Row facets as one jsonb document (masked upstream; bodies capped once
     * through the shared MockBodyTruncator gate — oversized payloads land with
     * a size-honest truncation tail instead of bloating the ledger).
     */
    private String snapshotJson(MockHitRowMessage row) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("method", row.method());
        snapshot.put("path", row.path());
        snapshot.put("query", row.query() == null ? Map.of() : row.query());
        snapshot.put("headers", row.headers() == null ? Map.of() : row.headers());
        snapshot.put("body", truncator.cap(row.body()));
        snapshot.put("stubName", row.stubName());
        return writeJson(snapshot, "{}");
    }

    /**
     * Response facets as one jsonb document, or SQL NULL when the row carries
     * no response plane at all. NULL and '{}' must stay tellable apart: absent
     * is a fact about the capture, empty is a fact about the document.
     */
    private String responseJson(MockHitRowMessage row) {
        boolean absent = row.responseStatus() == null
                && (row.responseHeaders() == null || row.responseHeaders().isEmpty())
                && row.responseBody() == null;
        if (absent) {
            return null;
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", row.responseStatus());
        response.put("headers", row.responseHeaders() == null ? Map.of() : row.responseHeaders());
        response.put("body", truncator.cap(row.responseBody()));
        return writeJson(response, null);
    }

    /** Verdict trail passes through when it is valid JSON, else an honest empty array. */
    private String traceJson(MockHitRowMessage row) {
        String trace = row.matchTrace();
        if (trace == null || trace.isBlank()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(objectMapper.readTree(trace));
        } catch (Exception ex) {
            // one broken trail must not cost the whole row — the miss verdict still stands
            return "[]";
        }
    }

    private String writeJson(Object value, String fallback) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return fallback;
        }
    }
}
