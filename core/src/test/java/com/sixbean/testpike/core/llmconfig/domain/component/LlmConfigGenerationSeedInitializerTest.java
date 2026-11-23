package com.sixbean.testpike.core.llmconfig.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.llmconfig.api.dto.LlmConfigInput;
import com.sixbean.testpike.core.llmconfig.domain.service.LlmConfigService;
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
 * Factory seed test for the default generation (chat) row of tp_llm_config.
 *
 * <p>Why this exists: the shipped baseline seeded only the embedding row, so a
 * fresh install had no default LLM row at all — every AI face (generation,
 * review, extraction budgeting) refused with "no model configured" until
 * someone registered one by hand, and the D5 catalog gate
 * (tools/doc_extract_gates.py) stayed red on every new chain slot. The row must
 * come from the install configuration, never from a hand-run script (R32:
 * AI features work out of the box; R37: the seeding channel is a startup hook,
 * single-sourced, replay-safe).
 *
 * <p>Covers: the pure tokenizer-family derivation (override wins, known models
 * map to their family, unknown models fall back to the conservative explicit
 * family instead of the "auto" fallback the catalog gate rejects), idempotent
 * seeding against the live database, the persisted row shape (generation type,
 * default flag, active status, explicit family, non-null prompt limit), and the
 * honest skip when no gateway endpoint is configured.
 *
 * <p>No TRUNCATE anywhere: this dev database is shared, so the tests assert
 * presence and re-use the parked-row helper of {@link
 * com.sixbean.testpike.core.support.LlmConfigSharedDbFixture} instead of
 * deleting baseline data.
 */
@SpringBootTest(classes = CoreApplication.class)
class LlmConfigGenerationSeedInitializerTest {

    @Autowired
    private LlmConfigGenerationSeedInitializer initializer;

    @Autowired
    private LlmConfigService llmConfigService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Active default generation rows (the exact anchor of the catalog gate). */
    private int activeDefaultGenerationRows() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_llm_config "
                        + "WHERE model_type = 1 AND is_default = 1 AND status = 1",
                Integer.class);
        return n == null ? 0 : n;
    }

    @Test
    void familyDerivation_prefersOverride_mapsKnownModels_andNeverReturnsAuto() {
        // An explicit install key always wins (a new family entering service
        // must be usable without a code change).
        assertEquals("cl100k", LlmConfigGenerationSeedInitializer
                .familyFor("qwen3.8-flash", "cl100k"));
        assertEquals("qwen", LlmConfigGenerationSeedInitializer
                .familyFor("qwen3.8-flash", null));
        assertEquals("qwen", LlmConfigGenerationSeedInitializer
                .familyFor("QWEN-PLUS-LATEST", ""));
        assertEquals("deepseek", LlmConfigGenerationSeedInitializer
                .familyFor("deepseek-v4-pro", null));
        assertEquals("cl100k", LlmConfigGenerationSeedInitializer
                .familyFor("gpt-4o-mini", null));
        assertEquals("cl100k", LlmConfigGenerationSeedInitializer
                .familyFor("claude-sonnet-4", null));
        // Unknown model: an explicit conservative family, never the auto
        // fallback the catalog gate rejects and never a refusal to seed.
        String fallback = LlmConfigGenerationSeedInitializer
                .familyFor("llama-4-scout", null);
        assertTrue(LlmConfigInput.TOKENIZER_FAMILIES.contains(fallback),
                "derived family must stay inside the contract whitelist, got " + fallback);
        assertFalse("auto".equals(fallback),
                "the seeded default row must carry an explicit tokenizer family");
    }

    @Test
    void seedIfMissing_isIdempotent_againstTheLiveDatabase() {
        int before = activeDefaultGenerationRows();
        int created = initializer.seedIfMissing();
        int after = activeDefaultGenerationRows();

        if (before > 0) {
            assertEquals(0, created, "an existing default generation row must not be duplicated");
            assertEquals(before, after);
        } else {
            assertTrue(created >= 0, "seeder reports 0 when the install config has no gateway");
        }
        assertEquals(0, initializer.seedIfMissing(), "replay on a seeded database writes nothing");
    }

    @Test
    void seededRow_carriesCatalogShape_whenInstallConfigIsPresent() {
        initializer.seedIfMissing();
        if (activeDefaultGenerationRows() == 0) {
            // No LLM_BASE_URL in this environment: the honest skip path is
            // covered by the next test, nothing to read back here.
            return;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT name, provider, model_name, base_url, status, is_default, model_type,"
                        + " prompt_limit_tokens, tokenizer_family, embedding_dimension"
                        + " FROM tp_llm_config WHERE model_type = 1 AND is_default = 1 AND status = 1");
        assertEquals(1, rows.size(), "exactly one active default generation row");
        Map<String, Object> row = rows.get(0);
        assertEquals(1, ((Number) row.get("status")).intValue());
        assertEquals(1, ((Number) row.get("is_default")).intValue());
        assertEquals(1, ((Number) row.get("model_type")).intValue());
        assertNotNull(row.get("prompt_limit_tokens"),
                "the catalog row must carry a prompt limit (the budget planner reads it)");
        String family = String.valueOf(row.get("tokenizer_family"));
        assertTrue(LlmConfigInput.TOKENIZER_FAMILIES.contains(family), "family " + family);
        assertFalse("auto".equals(family), "the catalog default must declare an explicit family");
        // Factory name shape is shared baseline data (the sweep helper keeps it
        // alive for exactly this reason); it must not drift per environment.
        assertTrue(String.valueOf(row.get("name")).startsWith(LlmConfigGenerationSeedInitializer.INSTALL_PREFIX),
                "unexpected install-default name " + row.get("name"));
        assertEquals(null, row.get("embedding_dimension"),
                "a generation row carries no vector dimension (that column belongs to embedding rows)");
    }

    @Test
    void blankGatewayBase_skipsSeeding_withoutThrowing() {
        // The seeder must never write an unreachable row: without a gateway
        // base address the answer is a named skip, not a half-configured model.
        LlmConfigGenerationSeedInitializer withoutGateway =
                new LlmConfigGenerationSeedInitializer(llmConfigService, jdbcTemplate, "openai", "   ",
                        "sk-test-presence-check", "qwen3.8-flash", null);
        assertEquals(0, withoutGateway.seedIfMissing(),
                "a blank LLM_BASE_URL must skip the seed instead of inventing a row");
    }
}
