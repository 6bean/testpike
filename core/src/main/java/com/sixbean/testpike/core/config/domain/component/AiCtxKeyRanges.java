package com.sixbean.testpike.core.config.domain.component;

import java.util.Map;

/**
 * Write-side value ranges + shared key/default names for the ai.ctx.*
 * long-context config key group .
 *
 * <p>Mirrors the AiChatKeyRanges / AiMcpSyncKeyRanges precedent: the K8
 * convention puts no CHECK constraint in the DB, this table is the single
 * source of intervals for the write guard in ConfigServiceImpl, and readers
 * (AiCtxConfigResolver in ai-service) clamp against the same intervals with
 * the DEFAULT_* constants below as the file-fallback tier. Key names and
 * factory defaults live here so the write guard, the seeded migration row and
 * the consuming assembler can never drift apart.
 *
 * <p>The former doc-summaries cache bool (ai.ctx.doc-summaries-cache) moved to
 * its real consumer family as ai.doc.summary-cache.enabled with V455, so this
 * group guards int intervals only.
 */
public final class AiCtxKeyRanges {

    /** Token budget of one tool block before it is downgraded to head/tail + pointer. */
    public static final String KEY_TOOL_BLOCK_MAX_TOKENS = "ai.ctx.tool-block-max-tokens";
    /** Newest dialog turns kept raw in the assembly; older ones fold into the rolling summary. */
    public static final String KEY_SESSION_KEEP_RECENT_TURNS = "ai.ctx.session-keep-recent-turns";
    /** Cap of injected project-memory items (never a full system-prompt dump). */
    public static final String KEY_MEMORY_MAX_ITEMS = "ai.ctx.memory-max-items";

    /** Fallback defaults (byte-aligned with the V350 seed — single source, no second default face). */
    public static final int DEFAULT_TOOL_BLOCK_MAX_TOKENS = 2000;
    public static final int DEFAULT_SESSION_KEEP_RECENT_TURNS = 6;
    public static final int DEFAULT_MEMORY_MAX_ITEMS = 30;

    /** Int keys the group interval-guards: key → {lower, upper} (mirror of the reader clamp). */
    private static final Map<String, int[]> INT_RANGES = Map.of(
            KEY_TOOL_BLOCK_MAX_TOKENS, new int[]{200, 20000},
            KEY_SESSION_KEEP_RECENT_TURNS, new int[]{1, 50},
            KEY_MEMORY_MAX_ITEMS, new int[]{1, 200});

    /** Prefix of the whole group (write-side entry check). */
    public static final String KEY_PREFIX = "ai.ctx.";

    private AiCtxKeyRanges() {
    }

    /** Interval for an ai.ctx.* int key, or null when not interval-guarded. */
    public static int[] rangeOf(String key) {
        return INT_RANGES.get(key);
    }

    /** True for any ai.ctx.* group key (single write-side dispatch check). */
    public static boolean isCtxKey(String key) {
        return key != null && key.startsWith(KEY_PREFIX);
    }
}
