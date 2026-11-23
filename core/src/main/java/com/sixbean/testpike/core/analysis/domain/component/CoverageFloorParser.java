package com.sixbean.testpike.core.analysis.domain.component;

/**
 * Parses the contract-coverage exit floor raw value from the K8 config cache.
 * <p>Shared by the plan exit check and the read-only analysis projection so the
 * key semantics stay single-sourced: 0, blank, absent or unparseable = gate off;
 * only 1..100 activates it (a misconfigured key must never explode either side).
 */
public final class CoverageFloorParser {

    private CoverageFloorParser() {
    }

    /** Floor percent from raw config text; anything outside 1..100 means off (0). */
    public static int parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return (value > 0 && value <= 100) ? value : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
