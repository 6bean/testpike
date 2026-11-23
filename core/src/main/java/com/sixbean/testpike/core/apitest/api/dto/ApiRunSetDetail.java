package com.sixbean.testpike.core.apitest.api.dto;

import java.util.List;

/**
 * Collection-run detail read-model : the ledger info plus every
 * child run as one step row, ordered by seq_no (launch tree order — never
 * completion order). Consumed by the admin bridge and the run-set panel.
 */
public record ApiRunSetDetail(ApiRunSetInfo ledger, List<ApiRunSetStep> steps) {

    /** Flatten ledger fields into the detail shape so the wire stays one object. */
    public long id() {
        return ledger.id();
    }

    public long collectionId() {
        return ledger.collectionId();
    }

    public long projectId() {
        return ledger.projectId();
    }

    public Long envId() {
        return ledger.envId();
    }

    public String name() {
        return ledger.name();
    }

    public String interruptionMode() {
        return ledger.interruptionMode();
    }

    public int status() {
        return ledger.status();
    }

    public int totalSteps() {
        return ledger.totalSteps();
    }

    public int passedCount() {
        return ledger.passedCount();
    }

    public int failedCount() {
        return ledger.failedCount();
    }

    public int skippedCount() {
        return ledger.skippedCount();
    }
}
