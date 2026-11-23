package com.sixbean.testpike.core.cluster.domain.component;

import com.sixbean.testpike.core.cluster.api.dto.ReleaseManifestInfo;
import com.sixbean.testpike.core.common.domain.component.VersionCompare;

/**
 * Pure function for release manifest version interval determination, the decision core of the protocol compatibility gate used by register/heartbeat gates.
 * Compatibility check cards consume it together, forbidding a second decision scheme; version extraction and segment-by-segment comparison reuse the {@link VersionCompare} common component.
 * <p>Fail-open missing-bound semantics: if either lower or upper bound is absent, that side does not gate, treating a manifest empty table or blank field as a not-imported state.
 * This does not block users and aligns with the acceptance line that a missing manifest means the upgrade center empty-state plain language; in-range means a closed interval including boundaries.
 */
public final class ReleaseRangeRules {

    private ReleaseRangeRules() {
    }

    /** Whether the version falls within the closed interval [min, max] (a null or blank bound allows that side; free text extracts the first version number). */
    public static boolean within(String version, String min, String max) {
        String v = VersionCompare.firstVersionOf(version);
        if (present(min) && VersionCompare.compare(v, VersionCompare.firstVersionOf(min)) < 0) {
            return false;
        }
        if (present(max) && VersionCompare.compare(v, VersionCompare.firstVersionOf(max)) > 0) {
            return false;
        }
        return true;
    }

    /**
     * Whether to allow (shared decision surface for the gate and check card): if manifest is null (not-imported state), allow all and must not crash the main chain.
     * A blank version means the legacy executor did not report, so allow with zero drift (missing data is not falsely judged); otherwise decide by the interval.
     */
    public static boolean allowed(ReleaseManifestInfo latest, String version) {
        if (latest == null || version == null || version.isBlank()) {
            return true;
        }
        return within(version, latest.executorVersionMin(), latest.executorVersionMax());
    }

    /** The interval plain-language text uses single-point wording and is consumed by the pool-entry rejection sentence, heartbeat alert, and compatDiff reason. */
    public static String rangeText(ReleaseManifestInfo latest) {
        String min = latest.executorVersionMin();
        String max = latest.executorVersionMax();
        boolean hasMin = present(min);
        boolean hasMax = present(max);
        if (hasMin && hasMax) {
            return min + "~" + max;
        }
        if (hasMin) {
            return "≥" + min;
        }
        if (hasMax) {
            return "≤" + max;
        }
        return "任意版本";
    }

    private static boolean present(String bound) {
        return bound != null && !bound.isBlank();
    }
}
