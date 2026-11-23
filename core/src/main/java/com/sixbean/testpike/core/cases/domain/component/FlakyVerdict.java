package com.sixbean.testpike.core.cases.domain.component;

/**
 * Flaky determination conclusion: pure function output of the window flip algorithm, with no behavior.
 *
 * @param flaky recent-window flips meet or exceed the threshold, judged as unstable.
 * @param flips the number of flips within the window (alternating pass-to-fail/timeout transitions).
 * @param samples the actual executions participating in the determination within the window (10 or fewer, including the minimum-sample-line check).
 * @param reason human-readable reason (non-empty when flaky=true, shown on hover of the list yellow label; otherwise null).
 * @param suggestClear suggestion to clear the isolated test case when its last five consecutive results are consistent (not automatic clearing).
 */
public record FlakyVerdict(boolean flaky, int flips, int samples, String reason, boolean suggestClear) {
}
