package com.sixbean.testpike.core.aichat.api.dto;

/**
 * Context waterline of one dialog session : the read
 * door for the ChatPanel waterline bar — how much of the per-session token
 * budget the sealed ledger has consumed, how many oldest user turns the
 * rolling summary has folded away, and how many pinned navigation facts ride
 * every turn. All numbers are computed server-side from the authoritative
 * stores (the message ledger, the K8 config values, the session's whitelisted
 * context); the front end never recomputes them.
 *
 * @param promptTokensUsed accumulated prompt tokens of the sealed messages
 * @param budgetTokens per-session cap; meaningless when unlimited
 * @param budgetUnlimited true when no usable cap is configured (honest
 * reading: an absent or unparsable value never
 * renders as "budget exhausted")
 * @param sealedUserTurns user turns sealed into the ledger
 * @param keepRecentTurns newest turns kept raw (clamped write-guard interval)
 * @param foldedTurns oldest turns folded into the rolling summary
 * @param pinnedFactsCount whitelisted scalar facts pinned on the session
 */
public record ChatContextWaterlineView(
        long promptTokensUsed,
        long budgetTokens,
        boolean budgetUnlimited,
        long sealedUserTurns,
        int keepRecentTurns,
        long foldedTurns,
        int pinnedFactsCount) {
}
