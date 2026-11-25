package com.sixbean.testpike.core.llmconfig.domain.component;

import com.sixbean.testpike.core.llmconfig.api.dto.LlmConfigInfo;
import com.sixbean.testpike.core.llmconfig.api.dto.LlmConfigInput;
import com.sixbean.testpike.core.llmconfig.domain.service.LlmConfigService;
import com.sixbean.testpike.common.config.CatalogInputWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Factory seed for the default generation (chat) row of tp_llm_config.
 *
 * <p>Gap this closes: the shipped baseline seeded only the embedding config, so
 * a freshly installed instance — or a freshly opened chain slot — had no
 * default generation model at all. Every AI face (case generation, review,
 * document extraction) then refused until an operator registered a row through
 * the settings page, and the extraction catalog gate
 * (tools/doc_extract_gates.py detector D5) stayed red because it requires one
 * active default row with an explicit tokenizer family. A manual registration
 * leaves no replay channel, so an accidental wipe stayed wiped.
 *
 * <p>Shape follows the embedding seeder exactly (single channel for factory
 * rows, R37): the values come from the install configuration
 * (.env keys LLM_PROVIDER / LLM_BASE_URL / LLM_API_KEY / LLM_MODEL, optional
 * LLM_TOKENIZER_FAMILY) and the write goes through
 * {@link LlmConfigService#create}, so encryption, name masking, the per-type
 * default clearing and the audit row stay single-sourced. No plaintext
 * credential ever reaches SQL seeds, git or logs — the key is only ever read
 * from the environment.
 *
 * <p>Anchor = an active default generation row exists, NOT "table empty":
 * rows registered by admins are never duplicated or touched. The name keeps the
 * {@code slot-default-<model>} shape used by
 * tools/verification/seed_slot_llm_default.py and protected by
 * {@code LlmConfigSharedDbFixture}, so test sweeps never delete baseline data.
 *
 * <p>Failure behaviour: without a gateway base address the seeder skips with a
 * named warning (a row that could not be called would fake readiness); seeding
 * never blocks startup and never fails the application.
 */
@Component
public class LlmConfigGenerationSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LlmConfigGenerationSeedInitializer.class);

    /** Install-default name shape (shared with the slot seeding tool and the L2 fixture). */
    public static final String INSTALL_PREFIX = "slot-default-";

    /** Explicit family used when the model name matches no known engine. */
    static final String FALLBACK_FAMILY = "sp-char";

    /**
     * Model-name markers mapped to the token estimator families of
     * ai-service TokenEstimatorRegistry. Conservative on purpose: an unknown
     * model gets the character-split estimator (over-estimates rather than
     * under-budgets the prompt), never the "auto" fallback the catalog gate
     * rejects.
     */
    private static final String[][] FAMILY_BY_MODEL_MARKER = {
            {"qwen", "qwen"},
            {"deepseek", "deepseek"},
            {"gpt", "cl100k"},
            {"o1", "cl100k"},
            {"o3", "cl100k"},
            {"claude", "cl100k"},
    };

    /** Presence anchor = the catalog gate's own D5 query face. */
    private static final String PRESENCE_SQL =
            "SELECT count(*) FROM tp_llm_config "
                    + "WHERE model_type = 1 AND is_default = 1 AND status = 1";

    private static final long SEED_OPERATOR_ID = 1L;

    private final LlmConfigService llmConfigService;
    private final JdbcTemplate jdbcTemplate;
    private final String provider;
    private final String gatewayBaseUrl;
    private final String gatewayApiKey;
    private final String modelName;
    private final String tokenizerFamilyOverride;
    private final String promptLimitOverride;

    public LlmConfigGenerationSeedInitializer(
            LlmConfigService llmConfigService,
            JdbcTemplate jdbcTemplate,
            @Value("${LLM_PROVIDER:openai}") String provider,
            @Value("${LLM_BASE_URL:}") String gatewayBaseUrl,
            @Value("${LLM_API_KEY:}") String gatewayApiKey,
            @Value("${LLM_MODEL:}") String modelName,
            @Value("${LLM_TOKENIZER_FAMILY:}") String tokenizerFamilyOverride,
            @Value("${LLM_PROMPT_LIMIT_TOKENS:}") String promptLimitOverride) {
        this.llmConfigService = llmConfigService;
        this.jdbcTemplate = jdbcTemplate;
        this.provider = provider;
        this.gatewayBaseUrl = gatewayBaseUrl;
        this.gatewayApiKey = gatewayApiKey;
        this.modelName = modelName;
        this.tokenizerFamilyOverride = tokenizerFamilyOverride;
        this.promptLimitOverride = promptLimitOverride;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = seedIfMissing();
            if (created > 0) {
                log.info("已补齐默认对话模型清单行（{}，端点来自 LLM_BASE_URL，"
                                + "令牌预估族={}，可在系统设置-大模型配置页调整）",
                        modelName, familyFor(modelName, tokenizerFamilyOverride));
            }
        } catch (RuntimeException e) {
            // Seeding must never block startup; the settings page can register the row by hand.
            log.warn("默认对话模型清单行种子失败（不影响启动，可在大模型配置页手工登记）: {}", e.getMessage());
        }
    }

    /**
     * Re-creates the default generation row when the anchor is missing.
     *
     * @return 1 when the row was seeded, 0 when one is already active or the
     *         install configuration names no callable gateway (replay-safe)
     */
    public int seedIfMissing() {
        Integer present = jdbcTemplate.queryForObject(PRESENCE_SQL, Integer.class);
        if (present != null && present > 0) {
            calibrateInstallDefaultRow();
            return 0;
        }
        if (isBlank(gatewayBaseUrl) || isBlank(modelName)) {
            log.warn("未配置 LLM_BASE_URL 或 LLM_MODEL（装机 .env 键），跳过默认对话模型清单行登记；"
                    + "补齐后重启服务或在大模型配置页手工登记即可");
            return 0;
        }
        llmConfigService.create(SEED_OPERATOR_ID, new LlmConfigInput(
                INSTALL_PREFIX + modelName.trim(),
                isBlank(provider) ? "openai" : provider.trim(),
                modelName.trim(),
                isBlank(gatewayApiKey) ? null : gatewayApiKey.trim(),
                gatewayBaseUrl.trim(),
                1, 1,
                "装机登记的默认对话模型：端点与模型名来自安装配置，密钥为空时在大模型配置页补填后即可使用；"
                        + "更换模型时令牌预估族会按模型名调整",
                limitFor(promptLimitOverride),
                familyFor(modelName, tokenizerFamilyOverride),
                LlmConfigInput.TYPE_GENERATION,
                null));
        return 1;
    }

    /**
     * Declared input window for a factory-seeded install-default row. An
     * explicit install key (LLM_PROMPT_LIMIT_TOKENS) inside the contract
     * range wins verbatim so an operator can state the vendor window exactly;
     * anything else (blank, non-numeric, out of range, or the blanket UI
     * default) lands on the conservative catalog floor — the row must never
     * start on the fake 1M window, which trips the startup consistency probe
     * and inflates the extraction budget past the endpoint's real window.
     */
    public static int limitFor(String rawOverride) {
        if (rawOverride == null || rawOverride.isBlank()) {
            return CatalogInputWindow.FALLBACK_INPUT_WINDOW_TOKENS;
        }
        try {
            int value = Integer.parseInt(rawOverride.trim());
            boolean usable = value >= LlmConfigInput.PROMPT_LIMIT_MIN
                    && value <= LlmConfigInput.PROMPT_LIMIT_MAX
                    && value != LlmConfigInput.DEFAULT_PROMPT_LIMIT_TOKENS;
            return usable ? value : CatalogInputWindow.FALLBACK_INPUT_WINDOW_TOKENS;
        } catch (NumberFormatException notNumeric) {
            return CatalogInputWindow.FALLBACK_INPUT_WINDOW_TOKENS;
        }
    }

    /**
     * Convergence channel for install-default rows seeded before the factory
     * calibration existed: a row still on the blanket default gets its
     * declared window through the same service write path (audit, masking
     * and replay-safety stay single-sourced). Rows registered by admins —
     * any name outside the install-default prefix — are never touched, and a
     * replay on a calibrated row writes nothing.
     */
    private void calibrateInstallDefaultRow() {
        Long rowId = jdbcTemplate.query(
                "SELECT id FROM tp_llm_config WHERE model_type = 1 AND status = 1 AND is_default = 1"
                        + " AND prompt_limit_tokens = ? AND name LIKE ?",
                rs -> rs.next() ? rs.getLong(1) : null,
                LlmConfigInput.DEFAULT_PROMPT_LIMIT_TOKENS, INSTALL_PREFIX + "%");
        if (rowId == null) {
            return;
        }
        LlmConfigInfo current = llmConfigService.get(rowId);
        if (current == null) {
            return;
        }
        llmConfigService.update(SEED_OPERATOR_ID, rowId, new LlmConfigInput(
                current.name(), current.provider(), current.modelName(),
                "", current.baseUrl(), current.status(), current.isDefault(),
                current.description(), limitFor(promptLimitOverride),
                current.tokenizerFamily(), LlmConfigInput.TYPE_GENERATION,
                current.embeddingDimension()));
        log.info("Install-default model row '{}' prompt limit recalibrated from the blanket "
                        + "default to the declared input window ({}) — value sourced from "
                        + "LLM_PROMPT_LIMIT_TOKENS or the conservative catalog floor; "
                        + "adjust per vendor-published window on the LLM settings page",
                current.name(), limitFor(promptLimitOverride));
    }

    /**
     * Tokenizer family for a model name: an explicit install key always wins,
     * then the known-model markers, then the conservative explicit fallback.
     */
    public static String familyFor(String model, String override) {
        if (!isBlank(override)) {
            return override.trim();
        }
        String low = model == null ? "" : model.toLowerCase();
        for (String[] marker : FAMILY_BY_MODEL_MARKER) {
            if (low.contains(marker[0])) {
                return marker[1];
            }
        }
        return FALLBACK_FAMILY;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
