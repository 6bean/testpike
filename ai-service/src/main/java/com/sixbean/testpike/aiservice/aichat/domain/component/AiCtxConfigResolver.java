package com.sixbean.testpike.aiservice.aichat.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigBooleans;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * K8 reader for the ai.ctx.* long-context key group (AP batch , six-layer
 * chain: seed values live in tp_sys_config, this resolver is the hot read +
 * clamp face, {@link AiCtxProperties} is the file fallback tier).
 *
 * <p>Same discipline as KnowledgeSearchConfigResolver: one gateway read per
 * call so a config-page edit takes effect on the next turn; absent / garbage /
 * out-of-range values NEVER reach the consumer — they fall back to the
 * configuration-file default with a warn line (degradation stays visible).
 * Write-side intervals are guarded once in core (AiCtxKeyRanges); the double
 * guard here is deliberate because ai-service may read a row written before a
 * future range change.
 *
 * <p>The former doc-summaries cache switch was NOT a member of this family:
 * its only consumer lives in the extraction chain, so V455 moved it to
 * ai.doc.summary-cache.enabled and DocGroupSummaryCache reads it directly.
 */
public class AiCtxConfigResolver {

    private static final Logger log = LoggerFactory.getLogger(AiCtxConfigResolver.class);

    /** Key names are single-sourced here (no scattered literals). */
    public static final String KEY_TOOL_BLOCK_MAX_TOKENS = "ai.ctx.tool-block-max-tokens";
    public static final String KEY_SESSION_KEEP_RECENT_TURNS = "ai.ctx.session-keep-recent-turns";
    public static final String KEY_MEMORY_MAX_ITEMS = "ai.ctx.memory-max-items";
    /** Over-budget self-heal master switch (ships ON). */
    public static final String KEY_HEAL_ENABLED = "ai.ctx.over-budget-heal.enabled";
    /** Pre-flight fold line: percent of the endpoint prompt limit. */
    public static final String KEY_HEAL_PREFLIGHT_RATIO = "ai.ctx.heal-preflight-ratio";

    /** Inclusive int intervals (mirror of the core write-side table). */
    private static final int[] TOOL_BLOCK_RANGE = {200, 20000};
    private static final int[] KEEP_RECENT_RANGE = {1, 50};
    private static final int[] MEMORY_ITEMS_RANGE = {1, 200};
    private static final int[] HEAL_RATIO_RANGE = {10, 100};

    private final CoreGateway coreGateway;
    private final AiCtxProperties properties;

    public AiCtxConfigResolver(CoreGateway coreGateway, AiCtxProperties properties) {
        this.coreGateway = coreGateway;
        this.properties = properties;
    }

    /** Token budget of one tool block before head/tail downgrade (default 2000). */
    public int toolBlockMaxTokens() {
        return resolveInt(KEY_TOOL_BLOCK_MAX_TOKENS, TOOL_BLOCK_RANGE,
                properties.getToolBlockMaxTokens());
    }

    /** Newest turns kept raw in the dialog assembly (default 6). */
    public int sessionKeepRecentTurns() {
        return resolveInt(KEY_SESSION_KEEP_RECENT_TURNS, KEEP_RECENT_RANGE,
                properties.getSessionKeepRecentTurns());
    }

    /** Cap of injected project-memory items (default 30). */
    public int memoryMaxItems() {
        return resolveInt(KEY_MEMORY_MAX_ITEMS, MEMORY_ITEMS_RANGE,
                properties.getMemoryMaxItems());
    }

    /** Dialog over-budget self-heal switch (K8 hot read; raw absent/illegal falls back to the file tier, ships ON). */
    public boolean healEnabled() {
        String raw = coreGateway.getSysConfig(KEY_HEAL_ENABLED);
        if (raw != null && !raw.isBlank() && !SysConfigBooleans.isLegal(raw)) {
            log.warn("K8 {}={} 非法（允许 true/false 或 1/0），回退配置文件 {}",
                    KEY_HEAL_ENABLED, raw, properties.isHealOverBudget());
        }
        return SysConfigBooleans.parse(raw, properties.isHealOverBudget());
    }

    /** Pre-flight fold line percent (default 95; interval 10~100). */
    public int healPreflightRatio() {
        return resolveInt(KEY_HEAL_PREFLIGHT_RATIO, HEAL_RATIO_RANGE,
                properties.getHealPreflightRatio());
    }

    private int resolveInt(String key, int[] range, int fallback) {
        String raw = coreGateway.getSysConfig(key);
        // Interval-tolerant int read folded into the shared K8 reader
        // (R40 second consumer: the capability reprobe scheduler demands the
        // same shape — one source, no copy). The visible warn lines stay.
        int value = com.sixbean.testpike.aiservice.llm.domain.component.SysConfigInts
                .parseInt(raw, range[0], range[1], fallback);
        if (raw != null && !raw.isBlank() && value == fallback
                && !String.valueOf(fallback).equals(raw.trim())) {
            log.warn("K8 {}={} 非法或越界（允许 {}~{}），回退配置文件 {}",
                    key, raw, range[0], range[1], fallback);
        }
        return value;
    }
}
