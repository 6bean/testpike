package com.sixbean.testpike.core.cases.api.dto;

/**
 * Result of one flaky re-probe sweep .
 * <p>Counters describe what the ledger round did; the disabled flag distinguishes the
 * short-circuit (hot key {@code ai.flaky.probe.enabled} off) from an empty round, so the
 * scheduler can log honestly and the sweep never performs hidden writes when turned off.
 *
 * @param opened probe sessions opened for newly quarantined cases (idempotent: a case
 * already holding an open session adds nothing)
 * @param closed sessions closed because their case is no longer quarantined
 * @param folded sessions whose stored streak/cursor actually changed this round
 * (a replay of the same result set folds nothing)
 * @param proposed restore proposals raised by reaching the green threshold
 * (a suggestion only - clearing the quarantine stays a human action)
 * @param withdrew pending proposals withdrawn because a new failure/timeout landed
 * @param dispatched re-probe runs handed to the shared execution channel this round
 * due sessions dispatch one run each through
 * ExecTaskService.create, inheriting its production guard and blacklist)
 * @param probeSkipped due sessions that could not dispatch honestly (case already has a
 * run in flight, no usable script, no environment configured, or the
 * execution channel refused) - each skip still paces its retry on the
 * probe interval so nothing hot-loops
 * @param disabled true when the sweep short-circuited on the disabled key (zero writes)
 */
public record FlakyProbeSweepResult(int opened, int closed, int folded,
                                    int proposed, int withdrew,
                                    int dispatched, int probeSkipped, boolean disabled) {
}
