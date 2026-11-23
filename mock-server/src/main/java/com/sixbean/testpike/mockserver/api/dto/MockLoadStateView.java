package com.sixbean.testpike.mockserver.api.dto;

import java.time.LocalDateTime;

/**
 * One-screen honest self-check of the traffic plane (AG design D-b). It carries
 * both faces side by side so a reader can answer "what is this plane serving
 * and can I trust it right now" without logs:
 * <ul>
 *   <li>loader face — which space key and content version are pinned, when the
 *   pin was last confirmed, and whether it is now stale (never-pinned, recent
 *   poll failures, or silent past the staleness window), with the last failure
 *   reason named instead of hidden;</li>
 *   <li>ledger face — how many masked rows are still staged locally, whether
 *   publishing is currently degraded (in-memory only), and how many rows were
 *   lost to buffer overflow (silent loss is forbidden, so the counter is
 *   exposed here).</li>
 * </ul>
 * Values pass through exactly as observed; nothing here is smoothed into a
 * prettier shape (a pretend-fresh state is a bug, not a UX choice).
 *
 * @param everLoaded         whether any snapshot has ever been pinned (false = fail-closed, 503 territory)
 * @param pinnedSpaceKey     routing handle of the pinned space (null before first load)
 * @param pinnedVersion      content version currently served (null before first load)
 * @param lastSuccessAt      when the pin was last confirmed by a successful poll (null never)
 * @param stale              honesty flag: pin not confirmed on recent polls or never loaded
 * @param consecutiveFailures unsuccessful poll count since the last success
 * @param lastError          reason of the most recent failure (null = no failure recorded)
 * @param hitBuffered        masked hit rows still waiting in the local staging buffer
 * @param hitDegraded        whether the last publish failed (ledger temporarily in-memory only)
 * @param hitOverflowDrops   rows lost to buffer overflow since start (expected: always 0)
 */
public record MockLoadStateView(
        boolean everLoaded,
        String pinnedSpaceKey,
        String pinnedVersion,
        LocalDateTime lastSuccessAt,
        boolean stale,
        int consecutiveFailures,
        String lastError,
        int hitBuffered,
        boolean hitDegraded,
        long hitOverflowDrops) {
}
