package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.aiservice.testsupport.AiCallLogTestCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Failed-provider-call ledger rows against the real schema ( face,
 * docs/change-eval adjudication option B). When a call dies before the
 * provider is resolved, the extraction seam physically cannot know the
 * provider; that absence is an honest state of the business, so the row
 * must land with provider = NULL (same convention as tokens = 0 for an
 * unknown cost) instead of being rejected by a NOT NULL constraint or
 * drowned in a sentinel value. Migration V540 drops the NOT NULL on
 * tp_ai_call_log.provider; model and function keep their constraint and
 * their writer-side normalization because every seam call carries them.
 * <p>Bidirectional fixture: an unresolved identity lands one row with a
 * NULL provider and the recorded error code; a resolved identity keeps
 * its exact value (no normalization drift for success-path callers).
 */
@SpringBootTest
class AiCallLogFailedCallRowTest {

    private static final long PROJECT_BASE = 989_140_000L;
    private static final long PROJECT = PROJECT_BASE + 1L;
    private static final String FUNCTION = "F5 分组摘要外呼";

    @Autowired
    private AiCallLogWriter writer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        AiCallLogTestCleanup.purgeProjectRange(jdbcTemplate, PROJECT_BASE);
    }

    private Map<String, Object> rowByToken(String token) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT provider, model, function, error_code, prompt_tokens, completion_tokens"
                        + " FROM tp_ai_call_log WHERE extract_token = ?", token);
        assertEquals(1, rows.size(), "exactly one ledger row per plane call, got " + rows.size());
        return rows.get(0);
    }

    /** An unresolved provider still produces the audit row, with the
     *  absence stored honestly as NULL and the failure code in place. */
    @Test
    void failedCallLandsOneRowWithNullProviderAndErrorCode() {
        String token = "t644-null-provider-" + UUID.randomUUID();

        writer.insert(PROJECT, null, "glm-4-air", FUNCTION, 0, 0, 63L, token, "50201");

        Map<String, Object> row = rowByToken(token);
        assertNull(row.get("provider"),
                "unresolved provider must land as NULL, not a sentinel: " + row.get("provider"));
        assertEquals("glm-4-air", row.get("model"));
        assertEquals(FUNCTION, row.get("function"));
        assertEquals("50201", row.get("error_code"));
        assertEquals(0, ((Number) row.get("prompt_tokens")).intValue());
        assertEquals(0, ((Number) row.get("completion_tokens")).intValue());
    }

    /** A blank provider (never-resolved variant from a misbehaving caller)
     *  collapses to the same honest NULL, never to a whitespace value that
     *  would break grouping queries. */
    @Test
    void blankProviderLandsAsNullNotWhitespace() {
        String token = "t644-blank-provider-" + UUID.randomUUID();

        writer.insert(PROJECT, "   ", "glm-4-air", FUNCTION, 0, 0, 12L, token, "50401");

        assertNull(rowByToken(token).get("provider"));
    }

    /** Negative fixture: a resolved provider keeps its exact value — the
     *  NULL face never swallows real identities for success-path callers. */
    @Test
    void resolvedProviderKeepsItsExactValue() {
        String token = "t644-resolved-provider-" + UUID.randomUUID();

        writer.insert(PROJECT, "openai", "glm-4-air", FUNCTION, 10, 5, 200L, token, null);

        Map<String, Object> row = rowByToken(token);
        assertEquals("openai", row.get("provider"));
        assertTrue(row.get("error_code") == null, "success rows never fake an error code");
    }
}
