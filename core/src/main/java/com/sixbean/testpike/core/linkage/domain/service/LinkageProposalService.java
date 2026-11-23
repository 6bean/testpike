package com.sixbean.testpike.core.linkage.domain.service;

import com.sixbean.testpike.common.linkage.LinkageImpactInfo;
import com.sixbean.testpike.core.linkage.api.dto.LinkageProposalInput;
import com.sixbean.testpike.core.linkage.api.dto.LinkageProposalRecord;
import com.sixbean.testpike.core.linkage.api.dto.LinkageProposalWrite;

/**
 * Proposal snapshot store for armed grouped-draft impacts ( script
 * repair trigger; the consuming one-click accept in reads these rows).
 * The generator that produces the content lives in the AI service and is
 * reused — this service only journals what it produced, keyed by the impact
 * row, and never touches downstream business tables.
 */
public interface LinkageProposalService {

    /**
     * Idempotent snapshot write keyed by impact_id: an identical replay
     * changes nothing (created=false), a newer base version re-arms a pending
     * row, a consumed row is frozen (a late proposal must not overwrite what
     * a human already accepted). Unknown impact = 404, unusable content
     * without a written reason = 400.
     */
    LinkageProposalWrite record(LinkageProposalInput input);

    /** Read one snapshot by its impact row; null when none exists yet. */
    LinkageProposalRecord get(long impactId);

    /**
     * Read the impact row a proposal request must be built from (the relay
     * consumer needs the ledger anchor — edge key, upstream coordinates,
     * project — to address one announcement per armed draft).
     */
    LinkageImpactInfo impactOf(long impactId);
}
