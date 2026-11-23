package com.sixbean.testpike.core.cluster.domain.service;

import com.sixbean.testpike.core.cluster.api.dto.ExecutorCompatView;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseManifestInfo;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseRegisterInput;

import java.util.List;

/**
 * The release manifest service uses a frozen document: "release manifest = upgrade authority".
 * <p>The consumer interfaces are the upgrade center (changelog drawer, compatibility health-check card, version banner) and the admin release registration endpoint.
 * The protocol compatibility gate validates register/heartbeat versions against the latest range.
 */
public interface ReleaseManifestService {

    /** Register or overwrite a release manifest (idempotent on the version-unique row); input validation rejects with human-readable messages. */
    ReleaseManifestInfo register(ReleaseRegisterInput input);

    /** The latest release manifest is the first row ordered by version descending; an empty table is null (an honest empty state when not imported, and the gate allows all based on it). */
    ReleaseManifestInfo latest();

    /**
     * One manifest row by exact version (upgrade pre-check consumes the artifact
     * array of the pinned version); null = not registered (caller keeps honest
     * empty pre-check, never fabricates rows).
     */
    ReleaseManifestInfo findByVersion(String version);

    /** The full manifest list is ordered by version descending, which equals the upgrade center consumption order. */
    List<ReleaseManifestInfo> listAll();

    /**
 * The compatibility diff is computed on the fly (health-check card data plane plus authoritative source for red-marked ledger rows): latest manifest range ×
     * All executor ledger rows are evaluated per executor; the decision spectrum reuses the single {@code ReleaseRangeRules} spectrum, and adding columns is forbidden.
     * Modifying the ExecutorInfo shape is forbidden (reading ledger rows uses the same pattern as the heartbeat gate).
     */
    List<ExecutorCompatView> compatDiff();

    /**
     * Hypothetical-range pre-check for the release-candidate confirm drawer: scores
     * every executor row against a not-yet-published [min, max] range instead of the
     * stored latest manifest, so the impact preview matches what confirmation would
     * enforce. Same ReleaseRangeRules spectrum as compatDiff() — no second verdict
     * source. Rejected with a human message when min > max.
     */
    List<ExecutorCompatView> compatDiffAgainst(String executorVersionMin, String executorVersionMax);
}
