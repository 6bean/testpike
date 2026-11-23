package com.sixbean.testpike.aiservice.llm.pipeline;

/**
 * Verdict of the stall guard for one would-be tool call (AX batch
 * item 8). {@code blocked} calls are refused BEFORE the tool runs — the
 * runner feeds {@code guidance} back to the model instead of dispatching.
 *
 * @param blocked true when the same call shape already failed
 * {@code blockAfter} times in a row
 * @param failureStreak consecutive same-fingerprint failures recorded so far
 * (0 for a call shape never seen)
 * @param errorKind triage bucket of the last recorded failure
 * (see {@link FailureTriage}), null when never seen
 * @param guidance strategy-change frame naming the three sanctioned
 * exits, null when not blocked
 */
public record StallVerdict(boolean blocked,
                           int failureStreak,
                           String errorKind,
                           String guidance) {

    private static final StallVerdict ALLOWED_FRESH = new StallVerdict(false, 0, null, null);

    /** Verdict for a call shape with no failure history. */
    public static StallVerdict allowedFresh() {
        return ALLOWED_FRESH;
    }

    /** Verdict for a call shape that already failed but stays under the bar. */
    public static StallVerdict allowedWith(int failureStreak, String errorKind) {
        return new StallVerdict(false, failureStreak, errorKind, null);
    }
}
