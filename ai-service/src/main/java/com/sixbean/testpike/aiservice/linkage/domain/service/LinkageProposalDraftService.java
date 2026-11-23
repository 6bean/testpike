package com.sixbean.testpike.aiservice.linkage.domain.service;

import com.sixbean.testpike.common.mq.LinkageProposalRequestMessage;

/**
 * AI-side answer to one linkage proposal request :
 * produce a repair draft through the single-source proposal kernel and post
 * the outcome back to the core ledger. Implemented by
 * {@code LinkageProposalDraftServiceImpl}; called only behind the consumer's
 * idempotent runner.
 */
public interface LinkageProposalDraftService {

    /** Generate the draft and land exactly one snapshot (valid or rejected-with-reason). */
    void answer(LinkageProposalRequestMessage message);
}
