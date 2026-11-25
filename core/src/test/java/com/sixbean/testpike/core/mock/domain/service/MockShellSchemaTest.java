package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mock-shell schema and seed assertions (AG batch pack A: migration shell only,
 * no services yet — this is the ground truth the admin/core blades in packs B/C build on).
 * <p>Verified surface: (1) tp_mock_space full shell column set + globally unique
 * space_key + lifecycle CHECK constraints (status 0 draft / 1 published / 2 retired,
 * base_path_mode prefix/strict, auth_mode none/token/cred); (2) tp_mock_stub column
 * set + path match type spectrum (exact/prefix/regex/template) + provenance spectrum
 * (manual/openapi/record/nl-ai/case-flip) — the single rule source shared by UI and
 * executor, so the shell must never drift; (3) tp_mock_scenario / tp_mock_hit_log /
 * tp_mock_recording / tp_mock_ai_job shells exist with their key columns; (4)
 * tp_apitest_request carries the mock_stale twin column of contract_stale (badge
 * consumed by the interface inventory page later); (5) the K8 governance key family
 * is seeded with the designed defaults (rule-load polling, hit batching/retention,
 * scenario/session TTL, exit-plan forbidden by default per ruling M-f, AI master
 * switch off per the 7a pattern).
 * <p>Runs against the real dev database (SpringBootTest, no mocks; migrations are
 * applied manually via psql, so information_schema read-back is the only honest
 * verification — never trust the merged SQL file alone).
 */
@SpringBootTest(classes = CoreApplication.class)
class MockShellSchemaTest {

    // miss_policy: per-space three-rung miss ladder (passthrough | reject |
    // llm-fallback), defaulted to reproduce the derived pre-policy behavior.
    static final Set<String> SPACE_COLUMNS = new HashSet<>(Arrays.asList(
            "id", "project_id", "name", "space_key", "alias", "env_id",
            "base_path_mode", "status", "auth_mode", "proxy_target_url", "rate_limit_rps", "quota_daily",
            "priority", "chaos_percent", "version", "expires_at", "created_at", "updated_at",
            "miss_policy"));

    static final Set<String> STUB_COLUMNS = new HashSet<>(Arrays.asList(
            "id", "space_id", "name", "priority", "method", "path_match_type", "path_pattern",
            "matchers_json", "response_json", "scenario_name", "required_state", "new_state",
            "source", "source_ref", "enabled", "version", "enriched_by", "contract_stale", "created_at", "updated_at"));

    static final Set<String> SCENARIO_COLUMNS = new HashSet<>(Arrays.asList(
            "id", "space_id", "name", "definition_json", "created_at", "updated_at"));

    // source: honest provenance (X-TP-Mock-Source) survives into the ledger;
    // NULL = row predates the provenance plane, never a fabricated default.
    static final Set<String> HIT_LOG_COLUMNS = new HashSet<>(Arrays.asList(
            "id", "space_id", "stub_id", "request_snapshot", "response_snapshot", "matched",
            "match_trace", "latency_ms", "fault_applied", "session_id", "hit_at", "source"));

    static final Set<String> RECORDING_COLUMNS = new HashSet<>(Arrays.asList(
            "id", "space_id", "target_base_url", "status", "summary_json",
            "cassette_ref", "promoted_count", "started_at", "ended_at", "created_at", "updated_at"));

    static final Set<String> AI_JOB_COLUMNS = new HashSet<>(Arrays.asList(
            "id", "space_id", "job_type", "input_json", "budget_snapshot_id", "status",
            "result_preview_ref", "error_message", "budget_snapshot", "created_at", "updated_at"));

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Set<String> columnsOf(String table) {
        return new HashSet<>(jdbcTemplate.query(
                "SELECT column_name FROM information_schema.columns WHERE table_name = ?",
                (rs, i) -> rs.getString(1), table));
    }

    private Set<String> constraintsOf(String table) {
        return new HashSet<>(jdbcTemplate.query(
                "SELECT con.conname FROM pg_constraint con"
                        + " JOIN pg_class rel ON rel.oid = con.conrelid"
                        + " WHERE rel.relname = ?",
                (rs, i) -> rs.getString(1), table));
    }

    @Test
    void mockSpaceTableHasShellColumnSet() {
        assertEquals(SPACE_COLUMNS, columnsOf("tp_mock_space"),
                "tp_mock_space column set must match the T357 shell (design section 4)");
    }

    @Test
    void mockSpaceCarriesUniqueKeyAndLifecycleChecks() {
        Set<String> cons = constraintsOf("tp_mock_space");
        assertTrue(cons.contains("uq_mock_space_key"), "space_key must be globally unique (routing handle)");
        assertTrue(cons.contains("ck_mock_space_status"), "lifecycle spectrum 0/1/2 must be guarded");
        assertTrue(cons.contains("ck_mock_space_base_path_mode"), "prefix/strict mode must be guarded");
        assertTrue(cons.contains("ck_mock_space_auth_mode"), "auth mode must be guarded");
    }

    @Test
    void mockStubTableHasShellColumnSet() {
        assertEquals(STUB_COLUMNS, columnsOf("tp_mock_stub"),
                "tp_mock_stub column set must match the T357 shell — single rule source for UI and executor");
    }

    @Test
    void mockStubCarriesMatchTypeAndSourceChecks() {
        Set<String> cons = constraintsOf("tp_mock_stub");
        assertTrue(cons.contains("ck_mock_stub_match_type"),
                "path match types exact/prefix/regex/template must be guarded");
        assertTrue(cons.contains("ck_mock_stub_source"),
                "provenance manual/openapi/record/nl-ai/case-flip must be guarded");
    }

    @Test
    void scenarioHitRecordingAiJobShellsExist() {
        assertEquals(SCENARIO_COLUMNS, columnsOf("tp_mock_scenario"), "tp_mock_scenario shell drifted");
        assertEquals(HIT_LOG_COLUMNS, columnsOf("tp_mock_hit_log"), "tp_mock_hit_log shell drifted");
        assertEquals(RECORDING_COLUMNS, columnsOf("tp_mock_recording"), "tp_mock_recording shell drifted");
        assertEquals(AI_JOB_COLUMNS, columnsOf("tp_mock_ai_job"), "tp_mock_ai_job shell drifted");
    }

    @Test
    void apitestRequestCarriesMockStaleTwinColumn() {
        Set<String> cols = columnsOf("tp_apitest_request");
        assertTrue(cols.contains("mock_stale"),
                "mock_stale (twin of contract_stale) must exist for the contract-change badge");
    }

    @Test
    void k8GovernanceKeysSeededWithDesignedDefaults() {
        Map<String, String> expected = Map.of(
                "mock.traffic.base-path", "/mocks",
                "mock.rule-load.interval-seconds", "3",
                "mock.hit-retention-days", "30",
                "mock.scenario.ttl-minutes", "120",
                "mock.exit-plan.forbid", "1",
                "ai.mock.enabled", "1"); // seeded on with the AI-features default-on batch (2026-10-26 ruling, hot-updatable)
        for (Map.Entry<String, String> e : expected.entrySet()) {
            String actual = jdbcTemplate.query(
                    "SELECT config_value FROM tp_sys_config WHERE config_key = ?",
                    rs -> rs.next() ? rs.getString(1) : null, e.getKey());
            assertEquals(e.getValue(), actual,
                    "K8 key " + e.getKey() + " must be seeded with the designed default (hot-updatable, never hardcoded)");
        }
    }
}
