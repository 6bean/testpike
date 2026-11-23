package com.sixbean.testpike.core.perf.domain.component;

import java.time.LocalDateTime;

/**
 * GA-2 environment occupant value object: what currently holds an environment
 * during a perf launch attempt. Exactly one facet is populated — an active perf
 * run (scenario name + start time + resolvable-or-not declared duration) or an
 * executing regression batch (batch name). {@code envName} is filled by the guard
 * so the rejection sentence can name the environment without a second query.
 * <p>Release estimate = started_at + duration + margin, only when the pinned
 * snapshot carries a positive duration (legacy rows fall back to honest wording —
 * the guard must never fabricate a time the executor cannot honour).
 */
public record PerfEnvOccupant(String scenarioName,
                              LocalDateTime startedAt,
                              long durationSeconds,
                              int marginMinutes,
                              String batchName,
                              String envName) {

    public static PerfEnvOccupant perfRun(String scenarioName, LocalDateTime startedAt,
                                          long durationSeconds, int marginMinutes) {
        return new PerfEnvOccupant(scenarioName, startedAt, durationSeconds, marginMinutes,
                null, null);
    }

    public static PerfEnvOccupant perfRun(String scenarioName, LocalDateTime startedAt,
                                          long durationSeconds, int marginMinutes,
                                          String envName) {
        return new PerfEnvOccupant(scenarioName, startedAt, durationSeconds, marginMinutes,
                null, envName);
    }

    public static PerfEnvOccupant batch(String batchName) {
        return new PerfEnvOccupant(null, null, 0, 0, batchName, null);
    }

    public static PerfEnvOccupant batch(String batchName, String envName) {
        return new PerfEnvOccupant(null, null, 0, 0, batchName, envName);
    }

    /** True when the occupant run declares a usable duration, so a release moment
     *  can be promised honestly. */
    public boolean hasReleaseEstimate() {
        return batchName == null && durationSeconds > 0;
    }

    /** Estimated release moment (started_at + declared duration + budget margin);
     *  only meaningful when {@link #hasReleaseEstimate()} is true. */
    public LocalDateTime estimatedReleaseAt() {
        return startedAt.plusSeconds(durationSeconds + marginMinutes * 60L);
    }
}
