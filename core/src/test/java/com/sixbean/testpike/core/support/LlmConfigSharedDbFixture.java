package com.sixbean.testpike.core.support;

import com.sixbean.testpike.core.llmconfig.domain.component.LlmConfigEmbeddingSeedInitializer;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shared L2 fixture for the tp_llm_config / tp_project factory rows (R37
 * baseline ruling: tests must never TRUNCATE factory data on the shared dev
 * database; the embedding default row seeded by LlmConfigEmbeddingSeedInitializer
 * and its per-type default flag are the frozen baseline shape).
 *
 * <p>Cleanup semantics: keep exactly the factory row (identified by the
 * seeder's frozen name — a test row that happens to match the presence anchor
 * by dimension must never survive, otherwise the per-type default index
 * rejects the flag restore below), then re-arm the factory default flag when
 * the service-level default switch cleared it, and remove the caller's
 * fixture projects together with their membership rows. tp_audit_log is an
 * append-only ledger and is left untouched on purpose.
 *
 * <p>The presence anchor is kept byte-identical to tools/ensure_baseline.py
 * LEDGER and LlmConfigEmbeddingSeedInitializer.PRESENCE_SQL, the identity to
 * the seeder constant (single-sourced, no copy); deadlock-victim retry reuses
 * {@link SharedDbTruncate}.
 */
public final class LlmConfigSharedDbFixture {

    /** Factory anchor: the active 1536-dim embedding row (baseline floor 1). */
    private static final String FACTORY_ANCHOR =
            "model_type = 2 AND embedding_dimension = 1536 AND status = 1";

    /**
     * Generation-domain install default prefix, byte-identical to the name
     * built by tools/verification/seed_slot_llm_default.py (the single seeding
     * channel for the default generation row). The row is shared-install
     * baseline data (R37): the D5 catalog gate in tools/doc_extract_gates.py
     * demands one active model_type=1 default, so the sweep must never wipe
     * it and the default flag must be re-armed when a promoted test row goes
     * away with it.
     */
    private static final String GEN_INSTALL_PREFIX = "slot-default-";

    private LlmConfigSharedDbFixture() {
    }

    /** Count of factory-anchor rows currently visible (baseline-aware assertions). */
    public static int factoryRowCount(JdbcTemplate jdbcTemplate) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_llm_config WHERE " + FACTORY_ANCHOR, Integer.class);
        return n == null ? 0 : n;
    }

    /**
     * Park the resident generation install-default rows (status=0) so a test
     * can construct the "no active generation row" world without deleting
     * baseline data (R37: factory/install rows are never TRUNCATEd by tests).
     * Callers must restore in a finally block via
     * {@link #restoreGenerationInstallDefaults}; the per-test cleanup here
     * also re-arms the default flag after any in-test default switch.
     */
    public static List<Long> parkGenerationInstallDefaults(JdbcTemplate jdbcTemplate) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM tp_llm_config WHERE model_type = 1 AND status = 1"
                        + " AND name LIKE '" + GEN_INSTALL_PREFIX + "%'", Long.class);
        if (!ids.isEmpty()) {
            jdbcTemplate.update("UPDATE tp_llm_config SET status = 0 WHERE id IN ("
                    + String.join(", ", ids.stream().map(String::valueOf).toList()) + ")");
        }
        return ids;
    }

    /** Put parked install-default rows back active (see {@link #parkGenerationInstallDefaults}). */
    public static void restoreGenerationInstallDefaults(JdbcTemplate jdbcTemplate, List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        String in = String.join(", ", ids.stream().map(String::valueOf).toList());
        jdbcTemplate.update("UPDATE tp_llm_config SET status = 1 WHERE id IN (" + in + ")");
    }

    /**
     * Run a test body with the generation install-default rows parked, always
     * restoring them afterwards (single-sourced try/finally for the
     * baseline-aware tests — never park without a guaranteed restore).
     */
    public static void withParkedGenerationInstallDefaults(JdbcTemplate jdbcTemplate, Runnable body) {
        List<Long> parked = parkGenerationInstallDefaults(jdbcTemplate);
        try {
            body.run();
        } finally {
            restoreGenerationInstallDefaults(jdbcTemplate, parked);
        }
    }

    /**
     * Delete all non-factory tp_llm_config rows, restore the factory default
     * flag (only when it drifted to 0 and no other type-2 default survived),
     * and delete the given fixture projects plus their membership rows.
     * Safe to call in @BeforeEach and @AfterEach; self-heals residue from
     * previously aborted runs.
     */
    public static void cleanPreservingFactory(JdbcTemplate jdbcTemplate, List<String> projectCodes) {
        String factory = LlmConfigEmbeddingSeedInitializer.SEED_ROW_NAME;
        String quotedCodes = String.join(", ",
                projectCodes.stream().map(c -> "'" + c.replace("'", "''") + "'").toList());
        SharedDbTruncate.executeAll(jdbcTemplate,
                // The generation install default (seed_slot_llm_default.py
                // name shape) is shared-install baseline, not test residue:
                // sweeping it made the D5 catalog gate red for every later
                // core batch. Test-created generation rows keep other names.
                "DELETE FROM tp_llm_config WHERE name <> '" + factory.replace("'", "''")
                        + "' AND NOT (model_type = 1 AND name LIKE '"
                        + GEN_INSTALL_PREFIX + "%')",
                // Re-arm the factory default only when the embedding domain lost
                // it entirely — never let the per-type unique index decide.
                "UPDATE tp_llm_config SET is_default = 1 WHERE name = '"
                        + factory.replace("'", "''")
                        + "' AND model_type = 2 AND status = 1 AND is_default = 0 "
                        + "AND NOT EXISTS (SELECT 1 FROM tp_llm_config t2 "
                        + "WHERE t2.model_type = 2 AND t2.is_default = 1 AND t2.status = 1)",
                // Same rule for the generation domain: a default-switching test
                // that cleared the flag restores it on the install row.
                "UPDATE tp_llm_config SET is_default = 1 WHERE name LIKE '"
                        + GEN_INSTALL_PREFIX + "%' AND model_type = 1 AND status = 1 "
                        + "AND is_default = 0 "
                        + "AND NOT EXISTS (SELECT 1 FROM tp_llm_config t2 "
                        + "WHERE t2.model_type = 1 AND t2.is_default = 1 AND t2.status = 1)",
                "DELETE FROM tp_user_project WHERE project_id IN "
                        + "(SELECT id FROM tp_project WHERE code IN (" + quotedCodes + "))",
                "DELETE FROM tp_project WHERE code IN (" + quotedCodes + ")");
    }
}
