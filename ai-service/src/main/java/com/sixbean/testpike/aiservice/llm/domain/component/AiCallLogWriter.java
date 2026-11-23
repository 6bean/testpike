package com.sixbean.testpike.aiservice.llm.domain.component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;


@Component
public class AiCallLogWriter {

    private static final Logger log = LoggerFactory.getLogger(AiCallLogWriter.class);

    private final NamedParameterJdbcTemplate namedJdbc;

    public AiCallLogWriter(NamedParameterJdbcTemplate namedJdbc) {
        this.namedJdbc = namedJdbc;
    }

    
    public void insert(Long projectId, String provider, String model, String function,
                       int promptTokens, int completionTokens, long latencyMs) {
        insert(projectId, provider, model, function, promptTokens, completionTokens,
                latencyMs, null, null);
    }

    /**
 * ledger face: same row plus the extraction generation token and
 * the error code of a failed provider call. A timeout/outage row keeps
 * cost at 0 honestly (tokens unknown), so the extraction plane burns are
 * reconcilable per generation .
 * Non-extraction writers pass null for both new values (zero drift).
 */
    public void insert(Long projectId, String provider, String model, String function,
                       int promptTokens, int completionTokens, long latencyMs,
                       String extractToken, String errorCode) {
        // tp_ai_call_log.provider became nullable (V540): an undetermined
        // provider identity (call failed before provider resolution) lands
        // honestly as NULL, the same convention as tokens = 0 for an
        // unknown cost. Normalizing it to a sentinel string would fake a
        // data source into every provider-grouped cost report (adjudication
        // option B). model/function keep NOT NULL in the schema, so their
        // absent values still land as "unknown" at this single choke point;
        // resolved values pass through untouched.
        String providerRow = blankToNull(provider);
        String modelRow = orUnknown(model);
        String functionRow = orUnknown(function);
        try {
            namedJdbc.update("""
                    INSERT INTO tp_ai_call_log (project_id, operator_id, provider, model, function,
                                                prompt_tokens, completion_tokens, cost, latency_ms,
                                                extract_token, error_code)
                    VALUES (:projectId, NULL, :provider, :model, :function,
                            :promptTokens, :completionTokens, 0, :latencyMs,
                            :extractToken, :errorCode)
                    """, new MapSqlParameterSource()
                    .addValue("projectId", projectId)
                    .addValue("provider", providerRow)
                    .addValue("model", modelRow)
                    .addValue("function", functionRow)
                    .addValue("promptTokens", promptTokens)
                    .addValue("completionTokens", completionTokens)
                    .addValue("latencyMs", (int) Math.min(Integer.MAX_VALUE, latencyMs))
                    .addValue("extractToken", extractToken)
                    .addValue("errorCode", errorCode));
        } catch (Exception e) {
            log.warn("AI 调用留痕写入失败 function={} project={} err={}（观测面不阻断）",
                    functionRow, projectId, e.getMessage());
        }
    }

    /** Absent ledger identity lands honestly as "unknown" (never a NOT NULL violation). */
    private static String orUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    /** An unresolved provider is a real business state once V540 made the
     *  column nullable: absent (null or whitespace) collapses to a plain
     *  NULL so grouped cost reports show one honest unknown bucket instead
     *  of a fabricated supplier name. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
