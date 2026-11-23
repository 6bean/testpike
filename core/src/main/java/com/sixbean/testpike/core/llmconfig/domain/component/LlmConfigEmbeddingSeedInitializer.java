package com.sixbean.testpike.core.llmconfig.domain.component;

import com.sixbean.testpike.core.llmconfig.api.dto.LlmConfigInput;
import com.sixbean.testpike.core.llmconfig.domain.service.LlmConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Factory seed for the default embedding row of tp_llm_config (R37 baseline
 * ruling: the dev database must always carry its factory data).
 *
 * <p>The row was historically hand-registered through /llm-configs (PRD v2.34,
 * model text-embedding-v4 at 1536 dims — the only Bailian model that satisfies
 * the frozen vector(1536) column of tp_knowledge_chunk). Manual registration
 * left no replay channel, so any accidental wipe stayed wiped; this seeder
 * makes the row self-healing on startup, same shape as the tp_tool seeders.
 *
 * <p>Storage compliance: the gateway master key is read from the environment
 * (LLM_API_KEY, never inlined) and goes through LlmConfigService.create, so
 * encryption, masking and the per-type default clearing stay single-sourced —
 * no plaintext credential ever reaches SQL seeds, git or logs.
 *
 * <p>Anchor = an active 1536-dim embedding row exists (the exact check of the
 * baseline ledger), NOT "table empty": rows registered by admins or other
 * batches are never duplicated or touched. Without LLM_BASE_URL configured
 * the seeder skips honestly (a row that cannot be called would fake readiness).
 */
@Component
public class LlmConfigEmbeddingSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LlmConfigEmbeddingSeedInitializer.class);

    /** Frozen factory shape (docs/14 §3.3 + PRD v2.34 ruling). */
    public static final String SEED_ROW_NAME = "百炼 text-embedding-v4（默认向量模型）";
    static final String PROVIDER = "dashscope-compatible";
    static final String MODEL_NAME = "text-embedding-v4";
    static final int EMBEDDING_DIMENSION = 1536;
    private static final long SEED_OPERATOR_ID = 1L;

    /** Presence anchor kept byte-identical to tools/ensure_baseline.py LEDGER. */
    private static final String PRESENCE_SQL =
            "SELECT count(*) FROM tp_llm_config "
                    + "WHERE model_type = 2 AND embedding_dimension = 1536 AND status = 1";

    private final LlmConfigService llmConfigService;
    private final JdbcTemplate jdbcTemplate;
    private final String gatewayBaseUrl;
    private final String gatewayApiKey;

    public LlmConfigEmbeddingSeedInitializer(
            LlmConfigService llmConfigService,
            JdbcTemplate jdbcTemplate,
            @Value("${LLM_BASE_URL:}") String gatewayBaseUrl,
            @Value("${LLM_API_KEY:}") String gatewayApiKey) {
        this.llmConfigService = llmConfigService;
        this.jdbcTemplate = jdbcTemplate;
        this.gatewayBaseUrl = gatewayBaseUrl;
        this.gatewayApiKey = gatewayApiKey;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = seedIfMissing();
            if (created > 0) {
                log.info("已补齐默认向量模型清单行（{}，{} 维，网关基址取自 LLM_BASE_URL）",
                        MODEL_NAME, EMBEDDING_DIMENSION);
            }
        } catch (RuntimeException e) {
            // Seeding must never block startup; the admin page can register the row manually.
            log.warn("默认向量模型清单行种子失败（不影响启动，可在大模型配置页手工注册）: {}", e.getMessage());
        }
    }

    /**
     * Re-creates the default embedding row when the baseline anchor is missing.
     *
     * @return 1 when the row was seeded, 0 when it already exists or no gateway
     *         endpoint is configured (idempotent, safe to call repeatedly)
     */
    public int seedIfMissing() {
        Integer present = jdbcTemplate.queryForObject(PRESENCE_SQL, Integer.class);
        if (present != null && present > 0) {
            return 0;
        }
        if (gatewayBaseUrl == null || gatewayBaseUrl.isBlank()) {
            log.warn("未配置 LLM_BASE_URL（OpenAI 兼容网关基址），跳过默认向量模型清单行注册；"
                    + "配置后重启 core 或在大模型配置页手工注册即可");
            return 0;
        }
        String apiKey = gatewayApiKey == null || gatewayApiKey.isBlank()
                ? null : gatewayApiKey.trim();
        llmConfigService.create(SEED_OPERATOR_ID, new LlmConfigInput(
                SEED_ROW_NAME, PROVIDER, MODEL_NAME, apiKey, gatewayBaseUrl.trim(),
                1, 1,
                "系统默认向量模型：经 OpenAI 兼容网关调用，输出 1536 维向量；"
                        + "更换向量模型需按页面提示重建向量表，否则检索会因维度不一致被拒绝",
                null, null, LlmConfigInput.TYPE_EMBEDDING, EMBEDDING_DIMENSION));
        return 1;
    }
}
