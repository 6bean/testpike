package com.sixbean.testpike.aiservice.llm.domain.component;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Ledger identity hygiene on tp_ai_call_log ( defect 2, refined by the
 * failed-row adjudication option B): a provider call whose identity was
 * never resolved used to carry a null provider into the INSERT, the row was
 * rejected by the NOT NULL constraint, and the writer's swallowed WARN
 * turned every failed attempt into observability noise while the audit row
 * itself vanished. The shared writer is the single choke point: provider
 * now lands honestly as NULL (V540 made the column nullable — a sentinel
 * would fake a data source into cost reports), while absent model/function
 * still normalize to "unknown" because those columns keep their NOT NULL
 * constraint and always carry a value on the extraction seam. Present
 * values pass through untouched (zero drift for success-path callers).
 */
class AiCallLogProviderFallbackTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final AiCallLogWriter writer = new AiCallLogWriter(jdbc);

    private MapSqlParameterSource captureParams() {
        ArgumentCaptor<MapSqlParameterSource> params =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        return params.getValue();
    }

    @Test
    void nullProviderIsWrittenAsHonestNullAfterColumnNullable() {
        writer.insert(9L, null, "glm-4-air", "doc-extract", 0, 0, 1200L);

        MapSqlParameterSource p = captureParams();
        assertNull(p.getValue("provider"),
                "undetermined provider lands as NULL, never a sentinel (V540 face)");
    }

    @Test
    void blankIdentityFieldsLandPerColumnContract() {
        writer.insert(9L, "  ", "", null, 5, 5, 80L);

        MapSqlParameterSource p = captureParams();
        assertNull(p.getValue("provider"), "whitespace provider collapses to NULL");
        assertEquals("unknown", p.getValue("model"));
        assertEquals("unknown", p.getValue("function"));
    }

    @Test
    void resolvedValuesKeepTheirExactForm() {
        writer.insert(9L, "openai", "glm-4-air", "classify", 10, 5, 42L);

        MapSqlParameterSource p = captureParams();
        assertEquals("openai", p.getValue("provider"));
        assertEquals("glm-4-air", p.getValue("model"));
        assertEquals("classify", p.getValue("function"));
    }
}
