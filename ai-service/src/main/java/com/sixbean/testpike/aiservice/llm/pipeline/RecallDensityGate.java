package com.sixbean.testpike.aiservice.llm.pipeline;

/**
 * EF batch : single-source decision face of the recall-density floor.
 *
 * <p>The floor turns a run's own item count against the estimated tokens it
 * was shown into one honest verdict: an enumeration-recall task that returns
 * fewer than {@code ceil(estimatedTokens x floor / 1000)} items read short of
 * the source's information density, whatever the per-block ledger says
 * (the live 8491/8639 pattern: 121k tokens, a handful of items, every other
 * face green). The doc extraction plane owned this arithmetic inline;
 * the spec-contract, judge question-gen, mock-draft and scenario-enumeration
 * planes are later consumers of the SAME decision and must not re-copy the
 * formula (R40), while each plane keeps its own hot key and its own
 * calibrated factory default (reuse-contract A3 — values are never borrowed
 * across domains, only the decision shape is shared).
 *
 * <p>Key face: {@code <domain>.recall-density-floor-per-ktoken}, allowed
 * band 0..50 shared with the core write-side gate; a value of 0 switches the
 * check off for that domain (the honest state of an uncalibrated or
 * shape-inapplicable plane, e.g. scenario enumeration whose floor stayed 0
 * after the row-shape finding of the third calibration window). Missing,
 * garbage or out-of-range reads fall back to the caller's domain default —
 * never to another domain's number.
 *
 * <p>Denominator face: estimated tokens (char_count x 0.75, the same
 * estimator the calibration windows booked every floor against), never the
 * raw char face and never a billing figure.
 */
public final class RecallDensityGate {

    /** Legal band shared with the core write-side family gate. */
    public static final double FLOOR_MIN = 0.0;
    public static final double FLOOR_MAX = 50.0;
    /** Estimated-token conversion booked by the calibration windows. */
    static final double TOKENS_PER_CHAR = 0.75;

    private RecallDensityGate() {
    }

    /** Hot-key parse with the conservative face: missing/garbage/out-of-range
     *  answers the caller's domain default; a legal 0 stays 0 (check off). */
    public static double parseFloor(String raw, double domainDefault) {
        try {
            double v = Double.parseDouble(raw == null ? "" : raw.trim());
            return v >= FLOOR_MIN && v <= FLOOR_MAX ? v : domainDefault;
        } catch (RuntimeException notANumber) {
            return domainDefault;
        }
    }

    /** The item count this source face owes the run at the given floor. */
    public static int expectedItems(int estimatedTokens, double floorPerKToken) {
        return (int) Math.ceil(estimatedTokens * floorPerKToken / 1000.0);
    }

    /** One honest verdict: floor 0 is check-off, otherwise a miss is strictly
     *  "fewer items than the ceiling of the floor". Meeting the floor passes. */
    public static boolean missed(double floorPerKToken, int itemCount, int estimatedTokens) {
        return floorPerKToken > 0 && itemCount < expectedItems(estimatedTokens, floorPerKToken);
    }

    /** Char→estimated-token conversion for planes without a planner face. */
    public static int estimateTokensFromChars(long charCount) {
        return (int) Math.ceil(charCount * TOKENS_PER_CHAR);
    }
}
