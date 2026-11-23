package com.sixbean.testpike.core.perf.domain.component;

import java.util.List;

/**
 * CF-1 ceiling-search finding — the one-screen conclusion of a LADDER
 * run: how far the system can be pushed before an SLA gate breaks, where latency growth
 * becomes nonlinear, and the SLO-safe operating watermark.
 * <p>All rates are nominal ladder rates (what the engine aimed at), never measured RPS —
 * the conclusion line states the caliber. {@code applicable=false} means the run is not
 * a well-formed ladder search (missing keys / no frames); the report then shows the
 * honest degradation note instead of fake numbers.
 *
 * @param applicable whether the ceiling analysis produced real tiers
 * @param tiers per-step rows in ladder order (tier/rate/pass/gate names)
 * @param breachStep 1-based index of the first step breaching an SLA gate, null = none
 * @param lastPassStep 1-based index of the last passing step before the breach, null if none
 * @param safeWatermarkRps SLO-safe watermark = last passing step's nominal rate x ratio
 * @param kneeStep 1-based index of the knee (nonlinear latency growth) step, null if none
 * @param kneeDescription human-readable growth pair behind the knee flag
 * @param conclusion one-screen conclusion sentence (human language, no JSON dump)
 * @param caliberNote caliber disclosure (nominal rate / watermark ratio in force)
 */
public record PerfCeilingFinding(boolean applicable,
                                 List<PerfCeilingTier> tiers,
                                 Integer breachStep,
                                 Integer lastPassStep,
                                 Double safeWatermarkRps,
                                 Integer kneeStep,
                                 String kneeDescription,
                                 String conclusion,
                                 String caliberNote) {

    /** Not-applicable shape with the honest reason (never a fabricated verdict). */
    public static PerfCeilingFinding notApplicable(String reason) {
        return new PerfCeilingFinding(false, List.of(), null, null, null, null, null, reason, reason);
    }

    /** One ladder step of the ceiling walk. */
    public record PerfCeilingTier(int step, int nominalRps, boolean passed,
                                  List<String> breachedGates, long requests) {
    }
}
