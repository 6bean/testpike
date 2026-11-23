package com.sixbean.testpike.core.linkage.domain.service;

/**
 * Consumption seam of the linkage impact ledger .
 * <p>When the human confirmation door lands a draft card that the bus minted
 * for an impact row, that ledger row must settle: status 4=consumed plus its
 * consumed_at stamp, inside the same transaction as the landing (a rolled-
 * back apply rolls the settle back too). The seam is keyed by the draft row
 * itself ({@code context_json->>'linkageImpactId'}), so a card without a
 * linkage anchor is an honest no-op and no blanket flip ever touches rows it
 * does not own.
 * <p>Kept as its own component in the linkage domain because the draft door
 * lives in aichat and the mint chain already depends aichat-ward from
 * linkage: a richer linkage collaborator here would close a bean cycle,
 * while this one depends only on the ledger table.
 */
public interface LinkageImpactConsumptionService {

    /**
 * Idempotent settle: flips a still-open ledger row once and only once,
 * then drains the matching change-analysis queue row for the same
 * upstream save package B: accepting the grouped card
 * is the reviewer's single verdict, so the queue must not keep asking
 * for the same fact a second time). Queue families are exactly the
 * registered analysis queues ({@code api_spec}, {@code doc}); any other
 * upstream family has no queue row and stays untouched.
 *
 * @param draftId the applied draft whose context may key one impact row
 * @param operatorId the confirmed human, stamped as {@code handled_by}
 * when this call settles a queue row
 * @return {@code true} when this call stamped the ledger row, {@code false}
 * when the draft keys no anchor or the row was already consumed
 */
    boolean consumeForDraft(long draftId, long operatorId);

    /**
     * Sweep settle for the queue side of the same seam: when a change-analysis
     * queue row is disposed, every still-armed ledger row of that upstream
     * save settles too, so the pinned panel and the queue never show two
     * open accounts of one disposition. Replays write nothing (the guard is
     * {@code consumed_at IS NULL}), which keeps the first stamp frozen.
     *
     * @param upstreamObjectType bus family token of the upstream object
     * @param upstreamObjectId   the upstream version/document that was saved
     * @return number of ledger rows settled by this call
     */
    int settleImpactsForUpstream(String upstreamObjectType, long upstreamObjectId);
}
