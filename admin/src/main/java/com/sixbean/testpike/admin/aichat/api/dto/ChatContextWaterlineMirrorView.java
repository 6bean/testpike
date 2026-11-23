package com.sixbean.testpike.admin.aichat.api.dto;

/**
 * Mirror of the core context-waterline door : the
 * admin bridge relays the numbers for the panel bar without recomputing —
 * budget use, turns folded into the rolling summary, pinned facts in play.
 * An unlimited budget reads as {@code budgetUnlimited=true} with a zero cap,
 * never as "budget exhausted".
 */
public record ChatContextWaterlineMirrorView(
        long promptTokensUsed,
        long budgetTokens,
        boolean budgetUnlimited,
        long sealedUserTurns,
        int keepRecentTurns,
        long foldedTurns,
        int pinnedFactsCount) {
}
