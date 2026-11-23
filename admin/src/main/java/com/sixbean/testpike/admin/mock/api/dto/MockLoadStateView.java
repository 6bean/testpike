package com.sixbean.testpike.admin.mock.api.dto;

import java.time.LocalDateTime;

/**
 * Admin-side mirror of the mock-server traffic-plane self-check face (AG
 * ruling D-b: staleness and ledger degradation are reportable, not just
 * logged). Field-for-field identical to the mock-server view on purpose —
 * the bridge passes values through verbatim so the inspector health bar can
 * render honest empties (null version = never pinned, drops = never hidden).
 * Cross-module classes stay in their own layer, hence this mirror instead of
 * a shared dependency (service-layering law: admin must not compile against
 * mock-server internals).
 *
 * @param everLoaded         whether any rule snapshot has ever been pinned (false = fail-closed)
 * @param pinnedSpaceKey     routing handle of the pinned space (null before first load)
 * @param pinnedVersion      content version currently served (null before first load)
 * @param lastSuccessAt      when the pin was last confirmed by a successful poll (null never)
 * @param stale              honesty flag: pin unconfirmed on recent polls or never loaded
 * @param consecutiveFailures unsuccessful poll count since the last success
 * @param lastError          reason of the most recent failure (null = none recorded)
 * @param hitBuffered        masked hit rows still staged in the local buffer
 * @param hitDegraded        whether the last ledger publish failed (in-memory only)
 * @param hitOverflowDrops   rows lost to buffer overflow since start (expected: 0)
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
