package com.sixbean.testpike.core.linkage.domain.service;

import com.sixbean.testpike.common.mq.LinkageProposalRequestMessage;

/**
 * Publisher of proposal-request announcements ( script repair
 * trigger). Same committed-data discipline as {@link
 * ObjectChangedEventPublisher}: the send is deferred to afterCommit so a
 * rolled-back detection pass can never summon an AI draft for a ledger row
 * that does not exist.
 */
public interface LinkageProposalRequestPublisher {

    /** Announce one armed draft; failures are logged, never propagated. */
    void publish(LinkageProposalRequestMessage message);
}
