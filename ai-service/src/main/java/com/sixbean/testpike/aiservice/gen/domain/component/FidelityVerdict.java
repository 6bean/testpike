package com.sixbean.testpike.aiservice.gen.domain.component;

import java.util.List;

/**
 * Verdict of the faithfulness checker .
 *
 * @param passed no dispute between the generated claims and the source blocks
 * @param disputes named disputes, each quoting the offending fragment so the
 * regeneration feedback can point at it (one line per claim)
 * @param checkedClaims audited claim count (0 = nothing was auditable)
 * @param matchedClaims claims backed by the source blocks
 * @param fidelityRatio matchedClaims / checkedClaims, 1.0 when nothing was
 * auditable; the first signal of the measured confidence
 * composite (adjudication item 5)
 */
public record FidelityVerdict(boolean passed, List<String> disputes, int checkedClaims,
                              int matchedClaims, double fidelityRatio) {
}
