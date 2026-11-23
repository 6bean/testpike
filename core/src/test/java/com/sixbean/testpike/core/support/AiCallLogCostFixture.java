package com.sixbean.testpike.core.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Single-source fixture row for cost-guard scenarios (R40: one implementation
 * per logic, consumers only reference it).
 *
 * <p>Inserts exactly one tp_ai_call_log row that charges {@code costYuan} to
 * the given project, with the fixed provider/model/function labels the cost
 * aggregation tests read back. This is the twin of the per-class private
 * helper it replaces, lifted out of CostBreakGuardTest and CostOverrideTest
 * when the second consumer appeared; the literal column list and values are
 * unchanged so behaviour does not drift.
 */
public final class AiCallLogCostFixture {

    private AiCallLogCostFixture() {
    }

    /** Insert one call-log row charging costYuan (yuan) to projectId. */
    public static void insert(JdbcTemplate jdbcTemplate, long projectId, double costYuan) {
        jdbcTemplate.update("""
                INSERT INTO tp_ai_call_log (project_id, operator_id, provider, model, function,
                                            prompt_tokens, completion_tokens, cost, latency_ms)
                VALUES (?, 1, 'openai', 'gpt-a', 'gen', 100, 200, ?, 1000)
                """, projectId, costYuan);
    }
}
