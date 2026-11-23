package com.sixbean.testpike.core.perf.domain.component;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which row-allocation strategies each load-test engine can honour .
 * <p>Single source for three consumers, so the wizard's disabled options, the
 * adapter dispatch guard and the policy door can never disagree:
 * <ul>
 * <li>GATLING — native queue feeder (SEQUENTIAL_EXCLUSIVE) + circular feeder
 * (CYCLE); no per-user private-slice feeder exists in the 3.13.5 Java DSL
 * (javap-proven, see GatlingAdapter), so VUSER_EXCLUSIVE is refused.</li>
 * <li>K6 — stride slicing per virtual user (VUSER_EXCLUSIVE) + shared pool
 * (CYCLE); no cross-VU atomic row lease primitive (K6Adapter), so
 * SEQUENTIAL_EXCLUSIVE is refused.</li>
 * </ul>
 * A null mode means "resolve the K8 default at dispatch" and is intentionally
 * not declared supported here — callers must resolve it to a concrete value
 * first (same order as the adapters: pinned snapshot → persisted column → K8).
 */
public final class PerfDatasetAllocPolicy {

    private static final Map<String, Set<String>> SUPPORTED = Map.of(
            "GATLING", Set.of(PerfRowAllocModes.SEQUENTIAL_EXCLUSIVE, PerfRowAllocModes.CYCLE),
            "K6", Set.of(PerfRowAllocModes.VUSER_EXCLUSIVE, PerfRowAllocModes.CYCLE));

    private PerfDatasetAllocPolicy() {
    }

    /** True when the engine natively carries the strategy (unknown engine/mode = false, never guess). */
    public static boolean supports(String engine, String mode) {
        return mode != null && supportedModes(engine).contains(mode);
    }

    /** Strategies the engine honours; empty for engines outside the known value domain. */
    public static Set<String> supportedModes(String engine) {
        if (engine == null) {
            return Set.of();
        }
        return SUPPORTED.getOrDefault(engine.toUpperCase(), Set.of());
    }

    /**
     * Strategies the engine honours minus a caller's already-taken set (used by
     * the policy door to explain what switching engines would unlock).
     */
    public static Set<String> unsupportedModes(String engine) {
        Set<String> missing = new LinkedHashSet<>(PerfRowAllocModes.VALUES);
        missing.removeAll(supportedModes(engine));
        return missing;
    }
}
