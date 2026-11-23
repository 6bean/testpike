package com.sixbean.testpike.core.cluster.domain.service;

import com.sixbean.testpike.core.cluster.api.dto.ClusterComponentInfo;

import java.util.List;

/**
 * The component version ledger service is the frozen-document version detection data plane within the version detection system.
 * <p>The ledger is the single source for the component view of the installkit ledger (no dual ledgers): one row per node and component family.
 * Heartbeat extended reporting (executor components[]), scheduled scan write-back, and upstream probing are included.
 * The three sources share this upsert channel, and support status is determined solely by {@code ComponentSupportRules}.
 */
public interface ClusterComponentService {

    /** Idempotent report (node×family single-row upsert): current overwrites,
     * requirement/target stay sticky when the reporter omits them; every write
     * re-scores the three-colour verdict plus the plain-word reason through the
     * shared judgement spectrum.
     */
    ClusterComponentInfo upsert(String nodeId, String componentFamily, String currentVersion,
                                String source, String requirement, String targetVersion);

    /**
 * Platform release target write-back (candidate promotion, ): stamps the
 * released version as target_version on every ledger row of the
 * release-tracked platform families and re-scores each touched row through
 * the shared judgement spectrum. Missing rows = honest no-op (ledger rows
 * are created by the agent enrollment side, never fabricated here).
 * @return number of ledger rows written back
 */
    int applyReleaseTarget(String targetVersion);

    /** The single-node component panorama is a row view of the version-by-component matrix. */
    List<ClusterComponentInfo> listByNode(String nodeId);

    /** The cluster-wide ledger is the panorama matrix data plane. */
    List<ClusterComponentInfo> listAll();
}
