package com.sixbean.testpike.aiservice.linkage.infrastructure.mq;

import com.sixbean.testpike.aiservice.linkage.domain.service.LinkageProposalDraftService;
import com.sixbean.testpike.common.idempotent.Idempotent;
import com.sixbean.testpike.common.mq.LinkageProposalRequestMessage;
import org.springframework.stereotype.Component;

/**
 * Idempotent execution leg of the linkage proposal consumer ;
 * Classify/CaseCleanup runner precedent — the consumer wraps this call in
 * {@code MqIdempotentConsumeSupport.consumeOnce}). Key shape
 * {@code linkage-proposal:{impactId}:{upstreamVersion}}: a same-version
 * redelivery (requeue / DLQ replay) replays the first token and is dropped as
 * DUPLICATE, while a re-armed impact from a NEW upstream version is a new key
 * and runs a fresh proposal — matching the impact ledger's versioning.
 */
@Component
public class LinkageProposalIdempotentRunner {

    private final LinkageProposalDraftService draftService;

    public LinkageProposalIdempotentRunner(LinkageProposalDraftService draftService) {
        this.draftService = draftService;
    }

    /** Runs the proposal leg once and echoes this delivery's token (replay returns the first token). */
    @Idempotent(key = "'linkage-proposal:' + #message.impactId() + ':' + #message.upstreamVersion()")
    public String run(LinkageProposalRequestMessage message, String token) {
        draftService.answer(message);
        return token;
    }
}
