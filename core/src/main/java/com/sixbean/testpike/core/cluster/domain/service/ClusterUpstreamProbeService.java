package com.sixbean.testpike.core.cluster.domain.service;

import com.sixbean.testpike.core.cluster.api.dto.ClusterUpstreamProbeResult;

/**
 * Upstream version detection writes back to the golden baseline service (version detection system, frozen-document scan loop).
 * Upstream version detection.
 * <p>The semantics are to probe the latest upstream version once for each engine-runtime component family of upstream sources already registered in the ledger (same family).
 * Multiple node rows share one probe snapshot, repeated per-node outbound calls are prohibited, and after writing back to the ledger via target upsert, the decision spectrum is determined.
 * The tri-color plus gray state is automatically re-evaluated with no new evaluation code and channel reuse.
 * <p>The honest boundary is that probe failure or unreachability means no target is written for that family, the original baseline is retained stickily, and the result leaves a trace.
 * The trace records external network probe unreachable, old values must not be misrepresented as latest, and the platform-side family target is the release manifest surface.
 * It is created alongside, and this service must not run ahead.
 */
public interface ClusterUpstreamProbeService {

    /**
     * The entire ledger is probed once, with low-frequency scheduled consumption and no repeated full-table outbound calls per round.
 * <p>The enterprise intranet uses the mirror source key installkit.mirror-base-url, which is read live and rerouted with the same standard as -5.
     * Secondary caching is prohibited, hot updates take effect immediately, and the GitHub releases surface is not overridden by npm/simple mirror rewrite semantics and remains the official source.
     * <p>When the ledger hits a platform family (core/admin/api/ai-service/frontend/mock-server)
     * one aggregated release-feed probe is merged in (no per-family outbound calls);
     * the result lands in the candidate table (awareness plane).
     */
    ClusterUpstreamProbeResult probeTargets();

    /**
     * Platform version candidate detection performs one aggregated outbound call to the release feed as the awareness surface.
     * <p>Results are written back to the candidate table tp_release_candidate in the detected state, and writing to the authoritative table is prohibited.
     * The authoritative table tp_release_manifest is not written; release is a manual authoritative action (red line of the two-state model).
     * <p>The honest three-type trace is that blank repo configuration means explicitly disabled and not probed, and unreachable means sticky zero candidate writes.
     * Old values must not be misrepresented; a frame missing tag_name means structural drift and must not be guessed; tokens are injected only from configuration (.env).
     * They must not be persisted to the database or written to logs.
     */
    ClusterUpstreamProbeResult probeReleaseFeed();
}
