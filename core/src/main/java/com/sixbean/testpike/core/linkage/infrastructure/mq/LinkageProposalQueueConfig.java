package com.sixbean.testpike.core.linkage.infrastructure.mq;

import com.sixbean.testpike.common.mq.LinkageProposalRequestMessage;
import com.sixbean.testpike.common.mq.MqDeadLetterTopology;
import com.sixbean.testpike.core.events.domain.component.RabbitEventConfig;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Proposal-request relay queue : a
 * durable queue {@code testpike.linkage-proposal-request} bound to the shared
 * event exchange {@code testpike.events} with routing key
 * {@code linkage.proposal-requested}. One armed grouped-draft impact produces
 * exactly one announcement here; the proposal producer (AI service) consumes
 * it and reuses the single repair-proposal generator — the bus stays the only
 * coupling between the linkage ledger and the AI leg (no direct calls, no
 * second protocol).
 * <p>Failure topology mirrors the other consumer queues: retry exhausted
 * routes to the dead-letter queue keyed by the queue name.
 */
@Configuration
public class LinkageProposalQueueConfig {

    /** Queue name (durable — a pending proposal ask must survive a crash). */
    public static final String QUEUE_NAME = LinkageProposalRequestMessage.QUEUE_NAME;

    /** Routing key of the proposal-request announcement on the shared exchange. */
    public static final String ROUTING_PROPOSAL_REQUESTED = LinkageProposalRequestMessage.ROUTING_PROPOSAL_REQUESTED;

    @Bean
    public Queue linkageProposalRequestQueue() {
        return QueueBuilder.durable(QUEUE_NAME)
                .deadLetterExchange(MqDeadLetterTopology.DLX_EXCHANGE)
                .deadLetterRoutingKey(QUEUE_NAME)
                .build();
    }

    /** Dead-letter exchange (direct; declared once, idempotent re-declaration). */
    @Bean
    public DirectExchange linkageProposalDeadLetterExchange() {
        return new DirectExchange(MqDeadLetterTopology.DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue linkageProposalRequestDeadLetterQueue() {
        return QueueBuilder.durable(MqDeadLetterTopology.dlqName(QUEUE_NAME)).build();
    }

    @Bean
    public Binding linkageProposalDeadLetterBinding(Queue linkageProposalRequestDeadLetterQueue,
                                                   DirectExchange linkageProposalDeadLetterExchange) {
        return BindingBuilder.bind(linkageProposalRequestDeadLetterQueue)
                .to(linkageProposalDeadLetterExchange).with(QUEUE_NAME);
    }

    /** Business binding (exchange bean from RabbitEventConfig.caseEventExchange). */
    @Bean
    public Binding linkageProposalRequestBinding(Queue linkageProposalRequestQueue,
                                                 TopicExchange caseEventExchange) {
        return BindingBuilder.bind(linkageProposalRequestQueue)
                .to(caseEventExchange).with(ROUTING_PROPOSAL_REQUESTED);
    }
}
