package com.sixbean.testpike.core.cluster.domain.service;

import com.sixbean.testpike.core.cluster.api.dto.ClusterBaselineScanResult;

/**
 * This is the component version baseline scan write-back service (version detection scheme b, frozen document "scheduled scan write-back").
 * <p>The semantics are: for online executor nodes, obtain the supported interval from the catalog descriptor runtime_version_req reported by their engine.
 * Write the supported interval into the component ledger requirement baseline (after the baseline upsert, the decision spectrum automatically re-evaluates three colors plus gray, with no new decision code).
 * This service prohibits running ahead for the upstream golden-version probe on the target side.
 */
public interface ClusterVersionBaselineService {

    /**
     * The descriptor baseline reconciliation scan performs a single pass over the entire online side, is consumed by low-frequency scheduling, and prohibits full-table re-probing each round.
     * <p>For required runtimes with no ledger row, create a record marked gray "not reported" so missing reports are visible on the matrix side.
     * If a descriptor key is absent from the component family registry, honestly skip it and leave a trace (dirty values must not enter the ledger).
     */
    ClusterBaselineScanResult syncFromDescriptors();
}
