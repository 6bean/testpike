package com.sixbean.testpike.core.behavior.api.dto;

/**
 * Statistics over one golden-set replay comparison .
 * Purely derived from the paired samples: no drift rate exists without at
 * least one pair, and every pair must be answerable.
 *
 * @param sampleTotal pairs fed into the comparison
 * @param driftedCount pairs whose normalized outputs differ between the two versions
 * @param driftPercentPp share of drifted pairs in percentage points (0~100, two decimals)
 */
public record GoldenDriftStat(int sampleTotal, int driftedCount, java.math.BigDecimal driftPercentPp) {
}
