package com.sixbean.testpike.core.deadletter.domain.component;

/**
 * Runtime view of the four hot-update keys driving the auto-replay loop
 * (interval / per-round cap / max rounds / master switch — nothing
 * hard-coded, the values are re-read every round).
 * <p>Plain value object (no behavior beyond defaults); the scheduler reads it
 * fresh from {@code ConfigService} and the unit spectrum constructs it directly,
 * which is why the business entry takes the policy as a parameter instead of
 * reaching for config itself.
 *
 * @param enabled         master switch; false short-circuits the whole round
 * @param intervalSeconds cadence floor and the "fresh arrival = still down"
 *                        evidence window for every whitelisted class
 * @param batchLimit      hard cap of reposts in one round (anti replay-storm)
 * @param maxRounds       rows that reached this replay count without settling
 *                        are auto-voided, never reposted again
 */
public record DeadLetterAutoReplayPolicy(boolean enabled, int intervalSeconds,
                                         int batchLimit, int maxRounds) {

    /** Fail-closed policy shape when the round parameters are unusable. */
    public static DeadLetterAutoReplayPolicy off() {
        return new DeadLetterAutoReplayPolicy(false, 300, 50, 3);
    }
}
