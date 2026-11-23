package com.sixbean.testpike.core.cases.domain.component;

import org.springframework.transaction.support.TransactionSynchronization;

/**
 * Defers one quick-review auto-approve attempt past the committing transaction
 * . Keeping this as a named top-level synchronization — rather than
 * running the approval inside the result-relay transaction — guarantees two things:
 * the evidence-grade raise is committed and visible when the case row is read, and
 * a rejected approval (e.g. a concurrent human reviewer winning the version race)
 * rolls back alone instead of poisoning the relay commit.
 */
class QuickReviewAutoApproveSync implements TransactionSynchronization {

    private final AiReviewAutoApprovePolicy policy;
    private final long caseId;
    private final Long operatorId;

    QuickReviewAutoApproveSync(AiReviewAutoApprovePolicy policy, long caseId, Long operatorId) {
        this.policy = policy;
        this.caseId = caseId;
        this.operatorId = operatorId;
    }

    @Override
    public void afterCommit() {
        policy.settleQuietly(caseId, operatorId);
    }
}
