package com.sixbean.testpike.aiservice.llm.domain.component;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Extraction-generation ledger columns on tp_ai_call_log (DD batch ,
 * IT-XQ-05 / TC-XQ-12 storage contract).
 * <p>Pinned here: the shared call-log writer can persist the extraction
 * generation token and the error code of a failed provider call, so a
 * timeout or outage lands in the ledger with model/latency/error_code/
 * extract_token present and cost left honestly at 0. Legacy seven-argument
 * writers keep their exact shape (both new values null) — zero drift for
 * the fifteen existing success-path callers.
 */
class AiCallLogExtractLedgerTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final AiCallLogWriter writer = new AiCallLogWriter(jdbc);

    private MapSqlParameterSource captureParams() {
        ArgumentCaptor<MapSqlParameterSource> params =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        return params.getValue();
    }

    @Test
    void failureRowPersistsExtractTokenAndErrorCode() {
        writer.insert(7L, "openai", "glm-4-air", "doc-extract",
                0, 0, 300_123L, "gen-token-001", "50401");

        String sql = captureSql();
        assertTrue(sql.contains("extract_token"), "ledger insert must carry extract_token");
        assertTrue(sql.contains("error_code"), "ledger insert must carry error_code");

        MapSqlParameterSource p = captureParams();
        assertEquals("gen-token-001", p.getValue("extractToken"));
        assertEquals("50401", p.getValue("errorCode"));
        assertEquals("doc-extract", p.getValue("function"));
        assertEquals(300_123, ((Number) p.getValue("latencyMs")).intValue());
    }

    @Test
    void legacyInsertKeepsLedgerColumnsNull() {
        writer.insert(3L, "openai", "glm-4-air", "classify", 10, 5, 42L);

        MapSqlParameterSource p = captureParams();
        assertNull(p.getValue("extractToken"), "non-extraction writers stay token-less");
        assertNull(p.getValue("errorCode"), "success rows must not fake an error code");
    }

    private String captureSql() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(MapSqlParameterSource.class));
        return sql.getValue();
    }
}
