package com.sixbean.testpike.core.llmconfig.domain.component;

import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import com.sixbean.testpike.core.llmconfig.api.dto.LlmCatalogConsistencyReport;
import com.sixbean.testpike.core.llmconfig.api.dto.LlmConfigInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Consistency probe of the model catalog against the installation
 * environment .
 *
 * <p>Why it exists: a generation row left on the blanket default
 * prompt_limit_tokens silently pushes the extraction budget onto the
 * ai-service fallback window (ModelAdaptiveBudget.FALLBACK_CATALOG_TOKENS
 * = 128000, effective 128000 x 0.6 = 76800), which cannot hold a
 * 121,375-token PRD — the measured root cause of the 4.06% fidelity
 * incident (document 8639, EE ruling file). And when the default generation
 * row drifts from the installed LLM_* triple, the page and the wire
 * disagree about which endpoint actually serves calls.
 *
 * <p>Two verdicts, both scoped to enabled generation rows only (an
 * embedding row has no per-request input window, so leaving it on the
 * default is by design and must not be reported):
 * 1) uncalibrated: prompt_limit_tokens is NULL / non-positive / exactly
 * {@link LlmConfigInput#DEFAULT_PROMPT_LIMIT_TOKENS};
 * 2) env drift: LLM_MODEL / LLM_BASE_URL / LLM_API_KEY are compared
 * key-by-key against the default generation row, each only when the env
 * value is present — equality-only, the report names the row and the
 * column, never a value. The stored key is decrypted to compare; a
 * decryption failure counts as drift (it must not fold into "consistent").
 *
 * <p>No default row + a non-blank LLM_MODEL that no enabled generation row
 * carries: reported as a missing-route finding instead of a row-by-row
 * comparison.
 */
@Component
public class LlmCatalogConsistencyProbe {

    /** Installation env triple (same keys the ai-service direct channel reads). */
    public static final String ENV_KEY_MODEL = "LLM_MODEL";
    public static final String ENV_KEY_BASE_URL = "LLM_BASE_URL";
    public static final String ENV_KEY_API = "LLM_API_KEY";

    private final JdbcTemplate jdbcTemplate;
    private final ConfigCipher configCipher;

    public LlmCatalogConsistencyProbe(JdbcTemplate jdbcTemplate, ConfigCipher configCipher) {
        this.jdbcTemplate = jdbcTemplate;
        this.configCipher = configCipher;
    }

    /** Production entry: run the verdicts against the process environment. */
    public LlmCatalogConsistencyReport checkFromEnvironment() {
        return check(Map.of(
                ENV_KEY_MODEL, nullToBlank(System.getenv(ENV_KEY_MODEL)),
                ENV_KEY_BASE_URL, nullToBlank(System.getenv(ENV_KEY_BASE_URL)),
                ENV_KEY_API, nullToBlank(System.getenv(ENV_KEY_API))));
    }

    /**
     * Pure verdicts entry (tests and future consumers): {@code env} is a
     * snapshot of the installation triple, blank value = key unset = skip
     * that comparison. Database failures propagate as-is — the startup
     * listener owns folding them into a single warning line.
     */
    public LlmCatalogConsistencyReport check(Map<String, String> env) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, name, model_name, base_url, api_key, prompt_limit_tokens,"
                        + " is_default FROM tp_llm_config WHERE model_type = 1 AND status = 1"
                        + " ORDER BY is_default DESC, id");
        if (rows.isEmpty()) {
            return new LlmCatalogConsistencyReport(true, List.of(), List.of());
        }
        List<String> uncalibrated = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object limit = row.get("prompt_limit_tokens");
            int value = limit == null ? 0 : ((Number) limit).intValue();
            if (value <= 0 || value == LlmConfigInput.DEFAULT_PROMPT_LIMIT_TOKENS) {
                uncalibrated.add((String) row.get("name"));
            }
        }
        List<String> mismatches = new ArrayList<>();
        String envModel = blankToNull(env.get(ENV_KEY_MODEL));
        String envBase = blankToNull(env.get(ENV_KEY_BASE_URL));
        String envKey = blankToNull(env.get(ENV_KEY_API));
        Map<String, Object> defaultRow = rows.stream()
                .filter(r -> ((Number) r.get("is_default")).intValue() == 1)
                .findFirst().orElse(null);
        if (defaultRow != null) {
            collectDrift(defaultRow, envModel, envBase, envKey, mismatches);
        } else if (envModel != null) {
            boolean modelPresentAnywhere = rows.stream().anyMatch(
                    r -> envModel.equals(((String) r.get("model_name")).trim()));
            if (!modelPresentAnywhere) {
                mismatches.add("<installed " + ENV_KEY_MODEL + ">|model_name");
            }
        }
        return new LlmCatalogConsistencyReport(false, uncalibrated, mismatches);
    }

    /** Equality-only comparison recording column labels; never values. */
    private void collectDrift(Map<String, Object> row, String envModel, String envBase,
                              String envKey, List<String> mismatches) {
        String name = (String) row.get("name");
        if (envModel != null && !envModel.equals(trimToEmpty(row.get("model_name")))) {
            mismatches.add(name + "|model_name");
        }
        if (envBase != null && !envBase.equals(trimToEmpty(row.get("base_url")))) {
            mismatches.add(name + "|base_url");
        }
        if (envKey != null) {
            String stored = (String) row.get("api_key");
            if (!envKey.equals(decryptOrNull(stored))) {
                mismatches.add(name + "|api_key");
            }
        }
    }

    private String decryptOrNull(String cipher) {
        if (cipher == null || cipher.isBlank()) {
            return null;
        }
        try {
            return configCipher.decrypt(cipher);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static String trimToEmpty(Object value) {
        return value == null ? "" : ((String) value).trim();
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
