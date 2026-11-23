package com.sixbean.testpike.core.linkage.domain.service.impl;

import com.sixbean.testpike.core.events.domain.component.RabbitEventConfig;
import com.sixbean.testpike.core.common.domain.component.AfterCommitExecutor;
import com.sixbean.testpike.common.mq.LinkageProposalRequestMessage;
import com.sixbean.testpike.core.linkage.domain.service.LinkageProposalRequestPublisher;
import com.sixbean.testpike.core.linkage.infrastructure.mq.LinkageProposalQueueConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * RabbitMQ-backed proposal-request publisher — the relay half of the script
 * repair trigger. Send is deferred through {@link AfterCommitExecutor}
 * (committed ledger only, a rollback summons no AI draft) on the shared
 * {@code testpike.events} exchange with routing key
 * {@code linkage.proposal-requested} (constants and converter reused from
 * RabbitEventConfig, nothing duplicated; same posture as
 * RabbitObjectChangedEventPublisher). Publish failures are logged and never
 * block the detection consumer — the daily bus-vs-edge reconcile job
 * backstops an unanswered pending draft.
 */
@Service
public class RabbitLinkageProposalRequestPublisher implements LinkageProposalRequestPublisher {

    private static final Logger log =
            LoggerFactory.getLogger(RabbitLinkageProposalRequestPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final AfterCommitExecutor afterCommitExecutor;

    public RabbitLinkageProposalRequestPublisher(RabbitTemplate rabbitTemplate,
                                                 AfterCommitExecutor afterCommitExecutor) {
        this.rabbitTemplate = rabbitTemplate;
        this.afterCommitExecutor = afterCommitExecutor;
    }

    @Override
    public void publish(LinkageProposalRequestMessage message) {
        if (message == null || message.impactId() == null) {
            return;
        }
        afterCommitExecutor.execute(() -> doSend(message));
    }

    private void doSend(LinkageProposalRequestMessage message) {
        try {
            rabbitTemplate.convertAndSend(RabbitEventConfig.EXCHANGE,
                    LinkageProposalQueueConfig.ROUTING_PROPOSAL_REQUESTED, message);
            log.info("linkage proposal request published impactId={} edgeKey={}"
                            + " downstream={}:{} projectId={} upstreamVersion={}",
                    message.impactId(), message.edgeKey(), message.downstreamObjectType(),
                    message.downstreamObjectId(), message.projectId(), message.upstreamVersion());
        } catch (RuntimeException e) {
            log.error("linkage proposal request publish dropped impactId={} edgeKey={}"
                    + " (logged, detection flow unaffected)",
                    message.impactId(), message.edgeKey(), e);
        }
    }
}
