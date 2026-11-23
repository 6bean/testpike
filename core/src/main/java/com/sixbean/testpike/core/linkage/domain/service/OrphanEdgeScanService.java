package com.sixbean.testpike.core.linkage.domain.service;

import com.sixbean.testpike.core.linkage.api.dto.OrphanEdgeScanReport;

/**
 * Diff of live foreign-key columns against the change-linkage edge ledger
 * . Saving an upstream object may only
 * re-check downstream rows through registered edges, so every foreign key
 * onto an armed member's table must itself be registered — otherwise the
 * change loop silently skips that consumer and nobody notices until a user
 * acts on stale data.
 */
public interface OrphanEdgeScanService {

    /**
     * Sweeps foreign keys against the ledger once. Reads live state on every
     * call: no cache, so disabling an edge resurfaces its relation on the
     * very next scan.
     */
    OrphanEdgeScanReport scan();
}
