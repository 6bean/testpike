package com.sixbean.testpike.aiservice.linkage.infrastructure.mq;

import com.sixbean.testpike.common.idempotent.ConsumeOutcome;
import com.sixbean.testpike.common.idempotent.MqIdempotentConsumeSupport;
import com.sixbean.testpike.common.mq.LinkageProposalRequestMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * AI-service consumer leg of the script repair trigger (AN batch segment
 * 4). Core's change-linkage consumer relays each armed script grouped-draft
 * impact to {@link LinkageProposalRequestMessage#QUEUE_NAME} (queue and
 * binding declared once on the core side — this leg only listens, never
 * re-declares, so no second topology source). Each request is answered
 * through the reused {@code RepairProposalGenerator} and lands as exactly one
 * snapshot on {@code tp_linkage_proposal} via the core internal endpoint.
 * <p>Guards mirror the other consumer queues (Classify/CaseCleanup posture):
 * a request without its ledger anchors goes to the dead letter loudly instead
 * of a silent ack; members without a proposal producer stay pending on the
 * ledger (debug line, zero action); business failures rethrow for container
 * retry and dead-lettering (never swallowed — R53).
 */
@Component
public class LinkageProposalConsumer {

    private static final Logger log = LoggerFactory.getLogger(LinkageProposalConsumer.class);

    /**
     * Consume lease 30 min: the proposal leg runs through the model fallback
     * chain and every LLM hop may wait up to the R56 floor (>=800s read
     * timeout per hop) — the lease must outlive the worst legitimate single
     * run; crash self-heal is provided by the lock expiring.
     */
    static final long CONSUME_LEASE_MILLIS = 30 * 60 * 1000L;

    private final LinkageProposalIdempotentRunner idempotentRunner;
    private final MqIdempotentConsumeSupport consumeSupport;

    public LinkageProposalConsumer(LinkageProposalIdempotentRunner idempotentRunner,
                                   MqIdempotentConsumeSupport consumeSupport) {
        this.idempotentRunner = idempotentRunner;
        this.consumeSupport = consumeSupport;
    }

    @RabbitListener(queues = LinkageProposalRequestMessage.QUEUE_NAME)
    public void onMessage(LinkageProposalRequestMessage message) {
        if (message == null) {
            return;
        }
        if (message.impactId() == null || message.downstreamObjectId() == null
                || message.projectId() == null || message.upstreamObjectId() == null) {
            log.warn("linkage proposal request without anchors impactId={} downstream={}:{} "
                            + "upstream={}:{} projectId={} — rejected to the dead letter, "
                            + "never silently acked",
                    message.impactId(), message.downstreamObjectType(),
                    message.downstreamObjectId(), message.upstreamObjectType(),
                    message.upstreamObjectId(), message.projectId());
            throw new IllegalArgumentException(
                    "proposal request needs impact, downstream, upstream and project anchors");
        }
        if (!message.scriptRepairLeg()) {
            log.debug("linkage proposal request edgeKey={} downstream={}:{} has no proposal "
                            + "producer yet — stays pending on the ledger",
                    message.edgeKey(), message.downstreamObjectType(),
                    message.downstreamObjectId());
            return;
        }
        String dedupKey = "linkage-proposal:" + message.impactId() + ":" + message.upstreamVersion();
        String token = UUID.randomUUID().toString();
        ConsumeOutcome outcome = consumeSupport.consumeOnce(dedupKey, token,
                () -> idempotentRunner.run(message, token), CONSUME_LEASE_MILLIS);
        if (outcome == ConsumeOutcome.DUPLICATE) {
            log.info("linkage proposal request replay dropped dedupKey={} "
                    + "(first run's snapshot is already on the ledger)", dedupKey);
        }
    }
}
