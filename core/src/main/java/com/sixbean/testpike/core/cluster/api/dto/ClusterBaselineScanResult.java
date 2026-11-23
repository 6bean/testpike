package com.sixbean.testpike.core.cluster.api.dto;

import java.util.List;

/**
 * Write-back results of the component version baseline scan (version detection system b, scan-loop observability plane of the frozen document).
 *
 * @param nodesScanned Number of online nodes that entered the scan plane in this round.
 * @param baselinesWritten Number of baseline rows flushed into the ledger (node×family).
 * @param skippedRequirementKeys Descriptor requirement keys with no registered component family (honest audit trail; never pretend they are under management).
 */
public record ClusterBaselineScanResult(int nodesScanned, int baselinesWritten,
                                        List<String> skippedRequirementKeys) {

    public ClusterBaselineScanResult {
        skippedRequirementKeys = skippedRequirementKeys == null
                ? List.of() : List.copyOf(skippedRequirementKeys);
    }
}
