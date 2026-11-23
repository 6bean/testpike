package com.sixbean.testpike.core.cases.domain.component;

/**
 * Outcome of folding newly observed execution results into one flaky-probe session
 * .
 * <p>Value object only - it carries no state itself; the probe ledger row stays the single
 * source of truth and this is what the next sweep writes back from.
 *
 * @param greenStreak consecutive green runs after folding (reset by any failure/timeout)
 * @param terminalCount how many of the observed results were terminal (non-terminal ones
 * are not an outcome yet and are simply skipped)
 * @param proposeRestore the streak just reached the configured threshold and no proposal is
 * pending yet, so a restore suggestion should be raised for the human
 * confirmation door (raising it never clears the quarantine itself)
 * @param withdrewProposal a failure/timeout landed while a restore proposal was pending, so that
 * stale suggestion has to be dropped instead of left confirmable
 */
public record FlakyProbeDecision(int greenStreak, int terminalCount,
                                 boolean proposeRestore, boolean withdrewProposal) {
}
