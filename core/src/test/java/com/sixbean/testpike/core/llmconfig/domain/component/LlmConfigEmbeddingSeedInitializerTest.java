package com.sixbean.testpike.core.llmconfig.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Factory seed test for the default embedding row of tp_llm_config (baseline
 * ruling 2026-10-28 R37: the dev database must always carry the factory data,
 * so a permanent config row that was historically hand-registered via the
 * admin API gets re-created by a startup seeder instead of manual SQL).
 *
 * <p>Covers: idempotent seeding (anchor = active 1536-dim embedding row, not
 * "table empty"), persisted row shape (model_type=2 / dims=1536 / default /
 * active), ciphertext-only storage (DB column never equals the plaintext key;
 * decrypt round-trip proves it is the gateway master key), and the honest
 * skip path when no gateway endpoint is configured.
 *
 * <p>No TRUNCATE anywhere: the factory rows of other batches are shared on
 * this dev database (R37 fixture rule — presence assertions only).
 */
@SpringBootTest(classes = CoreApplication.class)
class LlmConfigEmbeddingSeedInitializerTest {

    @Autowired
    private LlmConfigEmbeddingSeedInitializer initializer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConfigCipher configCipher;

    private int activeEmbeddingRows() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_llm_config "
                        + "WHERE model_type = 2 AND embedding_dimension = 1536 AND status = 1",
                Integer.class);
        return n == null ? 0 : n;
    }

    @Test
    void seedIfMissing_leavesExistingRowUntouched_andIsIdempotent() {
        int before = activeEmbeddingRows();
        int created = initializer.seedIfMissing();
        int after = activeEmbeddingRows();

        if (before > 0) {
            // Anchor present => second consumer of the same facts: nothing written.
            assertEquals(0, created, "seeder must not duplicate an active 1536 embedding row");
            assertEquals(before, after);
        } else {
            // Factory row missing => re-create exactly one, replay-safe on rerun.
            assertEquals(1, created, "missing factory embedding row must be seeded");
            assertEquals(1, after);
            assertEquals(0, initializer.seedIfMissing(), "rerun on a seeded table is a no-op");
        }
    }

    @Test
    void seededRow_carriesFrozenShape_andCiphertextKey() {
        assertTrue(initializer.seedIfMissing() >= 0);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT provider, model_name, api_key, base_url, status, is_default, model_type,"
                        + " embedding_dimension FROM tp_llm_config "
                        + "WHERE model_type = 2 AND embedding_dimension = 1536 AND status = 1 "
                        + "ORDER BY id LIMIT 1");
        assertFalse(rows.isEmpty(), "active 1536-dim embedding row must exist after seeding");
        Map<String, Object> row = rows.get(0);
        assertEquals("dashscope-compatible", row.get("provider"));
        assertEquals("text-embedding-v4", row.get("model_name"));
        assertEquals(1, ((Number) row.get("is_default")).intValue());

        String stored = (String) row.get("api_key");
        String gatewayKey = System.getenv("LLM_API_KEY");
        if (gatewayKey == null || gatewayKey.isBlank()) {
            // No gateway key configured locally: row may carry no key, but never plaintext noise.
            assertTrue(stored == null || configCipher.isEnabled(),
                    "a non-null key column requires a working cipher channel");
            return;
        }
        assertNotNull(stored, "gateway master key must be stored when configured");
        assertFalse(gatewayKey.equals(stored), "api_key must never be stored in plaintext");
        assertTrue(configCipher.isEnabled(), "cipher channel must be active to read the row back");
        assertEquals(gatewayKey.trim(), configCipher.decrypt(stored),
                "decrypt round-trip must return the LiteLLM master key");
    }

    @Test
    void gatewayUnset_skipsHonestly_withoutWriting() {
        int before = activeEmbeddingRows();
        LlmConfigEmbeddingSeedInitializer offline =
                new LlmConfigEmbeddingSeedInitializer(null, jdbcTemplate, " ", "  ");
        assertEquals(0, offline.seedIfMissing(),
                "without a gateway base_url the seeder must skip (no fake row, no crash)");
        assertEquals(before, activeEmbeddingRows(), "skip path writes nothing");
    }
}
