package com.sixbean.testpike.aiservice.gen.domain.component;

/**
 * One measured-confidence outcome .
 *
 * @param score equal-weight mean of the signals that actually ran
 * (fidelity / coverage / source), 0..1
 * @param level 1 = high (score ≥ 0.75), 2 = mid (≥ 0.5), 3 = low — the same
 * scale the model self-report used, so persisted rows need no
 * schema change
 * @param low level == 3: the auto depth-limit trigger (the stage2 script
 * pass is skipped and the notification says honestly that no
 * script was produced)
 */
public record ConfidenceMeasure(double score, int level, boolean low) {
}
