package com.sixbean.testpike.core.support;

/**
 * Observability seam for {@link SharedDbTruncate}: notified with the 1-based attempt number
 * before each truncate run. Kept as a top-level type (project rule: no inner classes); the
 * public fixture signature stays unchanged so call sites have zero ripple.
 */
@FunctionalInterface
public interface TruncateAttemptListener {

    /** Called once per attempt, right before the statements of that attempt are executed. */
    void onAttempt(int attempt);
}
