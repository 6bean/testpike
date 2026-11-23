package com.sixbean.testpike.core.cluster.api.dto;

import java.util.List;

/**
 * Represents the upstream version probing write-back result (version detection system, observation surface of the frozen document "upstream version probing").
 *
 * @param familiesProbed Represents the number of component families whose probing succeeded and obtained the latest upstream version (golden baseline).
 * @param rowsWritten Represents the number of ledger rows written back to target (node×family; multiple node rows of the same family share the same golden baseline).
 * @param unresolvedNotes Represents notes for families that failed to refresh target (plain-language reasons: unreachable or not probed).
 *                           The honest surface means the ledger retains the original baseline and forbids old values from masquerading as current values or pretending all checks are green.
 * @param candidatesRecorded Represents the number of candidate versions newly persisted to database in detected form by release feed probing.
 *                           Only when the platform family is hit, one aggregated outbound call is merged; failure/drift/closed types are always 0.
 *                           The candidate table perception surface semantics are orthogonal to the ledger target surface.
 */
public record ClusterUpstreamProbeResult(int familiesProbed, int rowsWritten,
                                         List<String> unresolvedNotes,
                                         int candidatesRecorded) {

    public ClusterUpstreamProbeResult {
        unresolvedNotes = unresolvedNotes == null ? List.of() : List.copyOf(unresolvedNotes);
    }
}
