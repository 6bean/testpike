package com.sixbean.testpike.core.perf.domain.component;

/**
 * SPIKE pulse segment timing . One value object derives the four
 * sequential legs — rise (base to peak), hold (peak), fall (peak to base) and
 * tail (base) — from the declared total duration so both engines compile the
 * same pulse shape from the same arithmetic (single source, no drift).
 * <p>Split rule: rise/hold/fall each take duration/5 (floor, minimum 1s), tail
 * absorbs the remainder so the four legs always sum to exactly durationSeconds
 * (the old bug was two full-length legs totalling twice the declared window).
 */
public record PerfSpikeSegments(int riseSeconds, int holdSeconds,
                                int fallSeconds, int tailSeconds) {

    /** Rise/hold/fall share = denominator of the total duration (fixed shape rule). */
    private static final int SHARE_DIVISOR = 5;

    /**
     * Derive the four segment lengths from the declared pulse duration.
     * Durations below the minimum viable pulse (under 4 seconds) keep every
     * leg at 1s — an honest short spike rather than a zero-length segment the
     * engine would reject.
     */
    public static PerfSpikeSegments derive(int durationSeconds) {
        if (durationSeconds < 4) {
            return new PerfSpikeSegments(1, 1, 1, Math.max(durationSeconds - 3, 1));
        }
        int share = Math.max(durationSeconds / SHARE_DIVISOR, 1);
        int tail = durationSeconds - share * 3;
        return new PerfSpikeSegments(share, share, share, Math.max(tail, 1));
    }
}
