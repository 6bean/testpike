package com.sixbean.testpike.core.llmconfig.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import com.sixbean.testpike.core.llmconfig.api.dto.LlmCatalogConsistencyReport;
import com.sixbean.testpike.core.llmconfig.api.dto.LlmConfigInput;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Startup self-check of the model catalog against the installation
 * environment .
 *
 * <p>Two defect families the probe must name:
 * 1) generation rows still sitting on the blanket default
 * prompt_limit_tokens — the extraction budget then runs on the fallback
 * window instead of the real model window;
 * 2) the default generation row drifting from the installation env triple
 * (LLM_MODEL / LLM_BASE_URL / LLM_API_KEY) — the page and the wire
 * disagree.
 *
 * <p>Privacy invariant: the report never carries plaintext key/endpoint
 * values, only row names and column labels (equality-only comparison).
 *
 * <p>Fixtures insert unique-named rows and delete them by id in finally,
 * restoring the factory default row afterwards — no TRUNCATE, shared
 * factory rows stay untouched (R37).
 */
@SpringBootTest(classes = CoreApplication.class)
class LlmCatalogConsistencyProbeTest {

    @Autowired
    private LlmCatalogConsistencyProbe probe;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConfigCipher configCipher;

    private long insertGenRow(String name, String modelName, String baseUrl,
                              String plainKey, int promptLimit, int isDefault, int status) {
        jdbcTemplate.update("""
                INSERT INTO tp_llm_config (name, provider, model_name, api_key, base_url,
                    status, is_default, description, prompt_limit_tokens, tokenizer_family,
                    model_type, embedding_dimension)
                VALUES (?, 'probe-fixture', ?, ?, ?, ?, ?, 'T581 fixture', ?, 'auto', 1, NULL)
                """, name, modelName,
                plainKey == null ? null : configCipher.encrypt(plainKey),
                baseUrl, status, isDefault, promptLimit);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM tp_llm_config WHERE name = ?", Long.class, name);
    }

    private Long factoryDefaultGenId() {
        return jdbcTemplate.query(
                "SELECT id FROM tp_llm_config WHERE model_type = 1 AND is_default = 1",
                rs -> rs.next() ? rs.getLong(1) : null);
    }

    /** Seed fixture rows, run the body, then delete fixtures and restore the default flag. */
    private void withFixtures(java.util.function.Supplier<List<Long>> seed,
                              Function<List<Long>, Object> body) {
        Long originalDefaultId = factoryDefaultGenId();
        // Park the factory default row first: tp_llm_config carries a partial
        // unique index (one default per model_type), fixtures may claim it.
        jdbcTemplate.update("UPDATE tp_llm_config SET is_default = 0 WHERE model_type = 1");
        List<Long> ids = seed.get();
        try {
            body.apply(ids);
        } finally {
            for (Long id : ids) {
                jdbcTemplate.update("DELETE FROM tp_llm_config WHERE id = ?", id);
            }
            jdbcTemplate.update("UPDATE tp_llm_config SET is_default = 0 WHERE model_type = 1");
            if (originalDefaultId != null) {
                jdbcTemplate.update("UPDATE tp_llm_config SET is_default = 1 WHERE id = ?",
                        originalDefaultId);
            }
        }
    }

    private static Map<String, String> env(String model, String base, String key) {
        return Map.of("LLM_MODEL", model, "LLM_BASE_URL", base, "LLM_API_KEY", key);
    }

    @Test
    void probeBean_isRegisteredInContext() {
        assertNotNull(probe, "catalog consistency probe must be a managed component");
    }

    @Test
    void unsetPromptLimit_isNamed_inUncalibratedList() {
        String name = "t581-uncal-" + UUID.randomUUID().toString().substring(0, 8);
        withFixtures(
                () -> List.of(insertGenRow(name, "fixture-model", "http://fixture.invalid/v1",
                        null, LlmConfigInput.DEFAULT_PROMPT_LIMIT_TOKENS, 0, 1)),
                ids -> {
                    LlmCatalogConsistencyReport report = probe.check(env("", "", ""));
                    assertTrue(report.uncalibratedModels().contains(name),
                            "row left on the blanket default must be named, got "
                                    + report.uncalibratedModels());
                    return null;
                });
    }

    @Test
    void calibratedRow_isNotFlagged() {
        // A fixture carrying an explicit non-default limit must stay out of
        // the uncalibrated list (200000 != blanket default 1048576).
        String name = "t581-cal-" + UUID.randomUUID().toString().substring(0, 8);
        withFixtures(
                () -> List.of(insertGenRow(name, "fixture-model", "http://fixture.invalid/v1",
                        null, 200000, 0, 1)),
                ids -> {
                    LlmCatalogConsistencyReport report = probe.check(env("", "", ""));
                    assertFalse(report.uncalibratedModels().contains(name),
                            "explicitly calibrated row must not be flagged");
                    return null;
                });
    }

    @Test
    void defaultRowModelDrift_isReportedWithRowNameAndColumnName_only() {
        String name = "t581-drift-" + UUID.randomUUID().toString().substring(0, 8);
        String envModel = "fixture-env-model-" + UUID.randomUUID().toString().substring(0, 8);
        String envBase = "http://env-gateway.invalid/v1";
        withFixtures(
                () -> List.of(insertGenRow(name, "fixture-other-model", envBase,
                        null, 200000, 1, 1)),
                ids -> {
                    LlmCatalogConsistencyReport report = probe.check(env(envModel, envBase, ""));
                    assertTrue(report.envMismatches().stream()
                                    .anyMatch(m -> m.contains(name) && m.contains("model_name")),
                            "drift of the default row model must name row + column, got "
                                    + report.envMismatches());
                    return null;
                });
    }

    @Test
    void blankEnvValue_isSkipped_noMismatch() {
        // Equality-only comparison: an env key that is unset cannot disagree.
        String name = "t581-blankenv-" + UUID.randomUUID().toString().substring(0, 8);
        withFixtures(
                () -> List.of(insertGenRow(name, "any-model", "http://any.invalid/v1",
                        null, 200000, 1, 1)),
                ids -> {
                    LlmCatalogConsistencyReport report = probe.check(env("", "", ""));
                    assertTrue(report.envMismatches().stream().noneMatch(m -> m.contains(name)),
                            "blank env triple must produce no drift verdict, got "
                                    + report.envMismatches());
                    return null;
                });
    }

    @Test
    void report_neverCarriesEnvPlaintextValues() {
        String name = "t581-privacy-" + UUID.randomUUID().toString().substring(0, 8);
        String secretKey = "sk-fixture-secret-" + UUID.randomUUID().toString().substring(0, 8);
        String envBase = "http://privacy-gateway.invalid/v1";
        withFixtures(
                () -> List.of(insertGenRow(name, "fixture-model", "http://row-only.invalid/v1",
                        "sk-row-secret-zzz", 200000, 1, 1)),
                ids -> {
                    LlmCatalogConsistencyReport report =
                            probe.check(env("other-model", envBase, secretKey));
                    // Drift must actually be detected, otherwise below is vacuous.
                    assertTrue(report.hasIssues(), "fixture triple must produce findings");
                    String rendered = String.valueOf(report.envMismatches())
                            + report.uncalibratedModels() + report.toString();
                    assertFalse(rendered.contains(secretKey), "plaintext env key leaked");
                    assertFalse(rendered.contains("sk-row-secret-zzz"), "plaintext row key leaked");
                    assertFalse(rendered.contains(envBase), "endpoint value leaked");
                    assertFalse(rendered.contains("privacy-gateway"), "endpoint value leaked");
                    return null;
                });
    }

    @Test
    void emptyCatalog_degradesToCatalogEmptyFlag() {
        // Zero enabled generation rows => catalogEmpty verdict, no throw.
        // Enabled ids are captured first and restored exactly.
        String name = "t581-empty-" + UUID.randomUUID().toString().substring(0, 8);
        List<Long> enabledBefore = jdbcTemplate.queryForList(
                "SELECT id FROM tp_llm_config WHERE model_type = 1 AND status = 1", Long.class);
        withFixtures(
                () -> List.of(insertGenRow(name, "fixture-model", "http://fixture.invalid/v1",
                        null, 200000, 0, 1)),
                ids -> {
                    jdbcTemplate.update("UPDATE tp_llm_config SET status = 0 WHERE model_type = 1");
                    try {
                        LlmCatalogConsistencyReport report = probe.check(env("", "", ""));
                        assertTrue(report.catalogEmpty(),
                                "no enabled generation row => catalogEmpty verdict");
                    } finally {
                        for (Long id : enabledBefore) {
                            jdbcTemplate.update("UPDATE tp_llm_config SET status = 1 WHERE id = ?", id);
                        }
                    }
                    return null;
                });
    }
}
