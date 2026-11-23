package com.sixbean.testpike.core.perf.domain.component;

/**
 * Terminal coverage finding for one perf run .
 * <p>Answers the honest question the live curve and the report must never paper
 * over: did the seconds the run actually pressed all reach core, or is the tail
 * gone (crashed flush window, dropped partial batch)? A lost finding forces a
 * truth annotation on the run (event + stop-reason note) so old values can never
 * silently impersonate a complete curve.
 *
 * @param lost true when the stored coverage falls short of the pressed
 * span by more than the hot-updatable tolerance
 * @param coverageMaxSecond deepest aggregate frame already stored (never negative)
 * @param expectedSeconds span the run actually pressed, in seconds
 * (declared duration capped by elapsed run time)
 * @param missingSeconds expected coverage minus stored coverage (positive when lost)
 */
public record PerfDataLossFinding(boolean lost, int coverageMaxSecond,
                                  long expectedSeconds, long missingSeconds) {

    /** No judgment to make (unparsable run row / no coverage at all / no claim). */
    public static PerfDataLossFinding none() {
        return new PerfDataLossFinding(false, -1, 0L, 0L);
    }
}
