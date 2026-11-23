package com.sixbean.testpike.common.mq;

/**
 * Announcement that an armed grouped-draft impact now needs an AI draft for
 * its downstream row (AN batch script repair trigger; routing key
 * {@link #ROUTING_PROPOSAL_REQUESTED} on the shared {@code testpike.events}
 * exchange, durable queue {@link #QUEUE_NAME}). The proposal generator itself
 * lives in the AI service and is reused, never copied — this message is only
 * the request plus the ledger anchor it must answer to.
 * <p>Home: common.mq, the single-source slot for every cross-service wire
 * contract (CaseIngestEventMessage family precedent) — the producer is the
 * core service, the consumer is the AI service, so neither module may own the
 * shape privately.
 *
 * @param impactId tp_linkage_impact row that armed the draft (idempotency anchor)
 * @param edgeKey registry key of the edge that found the impact
 * @param upstreamObjectType bus token of the saved object, e.g. {@code case}
 * @param upstreamObjectId the object that was saved
 * @param upstreamVersion version of the upstream row this draft answers to
 * @param downstreamObjectType invalidated object type, e.g. {@code script}
 * @param downstreamObjectId invalidated downstream row id (the generator re-reads current content)
 * @param projectId owning project (H1 isolation dimension)
 * @param occurredAt epoch millis of the announcement
 */
public record LinkageProposalRequestMessage(
        Long impactId,
        String edgeKey,
        String upstreamObjectType,
        Long upstreamObjectId,
        Integer upstreamVersion,
        String downstreamObjectType,
        Long downstreamObjectId,
        Long projectId,
        Long occurredAt) {

    /** Queue holding the requests (durable — a pending proposal ask survives a crash). */
    public static final String QUEUE_NAME = "testpike.linkage-proposal-request";

    /** Routing key of the request announcement on the shared event exchange. */
    public static final String ROUTING_PROPOSAL_REQUESTED = "linkage.proposal-requested";

    /** Downstream member that has a proposal producer today (script repair leg). */
    public static final String MEMBER_SCRIPT = "script";

    /** Upstream member the script repair leg reads current content from. */
    public static final String MEMBER_CASE = "case";

    /**
     * True when this request is on the member pair the AI leg can answer now
     * (case -&gt; script). The repair kernel reads the script through its
     * owning case, so {@code upstreamObjectId} must be present. Other members
     * stay pending on the ledger until their edge batch ships a producer.
     */
    public boolean scriptRepairLeg() {
        return MEMBER_SCRIPT.equals(downstreamObjectType)
                && MEMBER_CASE.equals(upstreamObjectType)
                && upstreamObjectId != null;
    }
}
