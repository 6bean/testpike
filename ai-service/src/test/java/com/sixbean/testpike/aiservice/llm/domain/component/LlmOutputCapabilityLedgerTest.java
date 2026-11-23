package com.sixbean.testpike.aiservice.llm.domain.component;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Capability-ledger contract (V615): the accept-but-not-enforce observation
 * must survive inside the process even when the storage door is down (a
 * ledger outage may never block or degrade generation), reads must be
 * fail-soft to "no observation" (legacy form keeps riding), and a cached
 * verdict must not re-query the door on every call. The fail-soft branch is
 * pinned too (R43: a fallback with no exercised case is a silent idle).
 */
class LlmOutputCapabilityLedgerTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final LlmOutputCapabilityLedger ledger = new LlmOutputCapabilityLedger(jdbc);

    @Test
    void 观察落账_后续判定走缓存不再打库() {
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class), eq(Integer.class)))
                .thenReturn(List.of());
        assertFalse(ledger.isFormUnenforced("m1", LlmOutputCapabilityLedger.FORM_TOOLS_SCHEMA));

        ledger.observeNotEnforced("m1", LlmOutputCapabilityLedger.FORM_TOOLS_SCHEMA);
        assertTrue(ledger.isFormUnenforced("m1", LlmOutputCapabilityLedger.FORM_TOOLS_SCHEMA));
        verify(jdbc, times(1)).queryForList(anyString(), any(SqlParameterSource.class),
                eq(Integer.class));

        // Forms are independent rows of the same model.
        assertFalse(ledger.isFormUnenforced("m1", LlmOutputCapabilityLedger.FORM_SCHEMA_ONLY));
    }

    @Test
    void 读门不可达_按无观察继续_写门不可达_进程内判定仍生效() {
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class), eq(Integer.class)))
                .thenThrow(new RuntimeException("door down"));
        when(jdbc.update(anyString(), any(SqlParameterSource.class)))
                .thenThrow(new RuntimeException("db down"));

        assertFalse(ledger.isFormUnenforced("m2", LlmOutputCapabilityLedger.FORM_TOOLS_SCHEMA),
                "unreadable ledger must not accuse a clean endpoint");

        ledger.observeNotEnforced("m2", LlmOutputCapabilityLedger.FORM_TOOLS_SCHEMA);
        assertTrue(ledger.isFormUnenforced("m2", LlmOutputCapabilityLedger.FORM_TOOLS_SCHEMA),
                "the process keeps the truth it proved, even when the row write failed");
    }

    @Test
    void 库面既有观察_首次判定即加载生效() {
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class), eq(Integer.class)))
                .thenReturn(List.of(0));
        assertTrue(ledger.isFormUnenforced("m3", LlmOutputCapabilityLedger.FORM_TOOLS_SCHEMA),
                "a persisted not-enforced row steers calls right after a restart");

        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class), eq(Integer.class)))
                .thenReturn(List.of(1));
        assertFalse(new LlmOutputCapabilityLedger(jdbc)
                        .isFormUnenforced("m4", LlmOutputCapabilityLedger.FORM_TOOLS_SCHEMA),
                "an enforced row (probe re-confirmed) never steers");
    }

    @Test
    void staleObservationsQueryCarriesTheIntervalWindowAsNegativeHours() {
        when(jdbc.query(eq(LlmOutputCapabilityLedger.FIND_STALE_SQL),
                any(SqlParameterSource.class),
                any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.<String[]>of(new String[]{"m", LlmOutputCapabilityLedger.FORM_TOOLS_SCHEMA}));

        List<String[]> due = ledger.findStaleObservations(24);

        assertEquals(1, due.size());
        ArgumentCaptor<SqlParameterSource> sent =
                ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).query(eq(LlmOutputCapabilityLedger.FIND_STALE_SQL),
                sent.capture(), any(org.springframework.jdbc.core.RowMapper.class));
        assertEquals(-24, ((MapSqlParameterSource) sent.getValue())
                .getValue("negativeHours"),
                "due window = now() - interval hours expressed as a negative interval");
    }

    @Test
    void staleObservationsDoorOutageAnswersEmptyNeverFakeDueRows() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                any(org.springframework.jdbc.core.RowMapper.class)))
                .thenThrow(new RuntimeException("door down"));

        assertTrue(ledger.findStaleObservations(24).isEmpty(),
                "P18 three-state: an unreadable ledger is never presented as due material");
    }
}
