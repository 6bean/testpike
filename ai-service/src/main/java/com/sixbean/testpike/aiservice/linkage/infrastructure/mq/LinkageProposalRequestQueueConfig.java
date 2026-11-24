package com.sixbean.testpike.aiservice.linkage.infrastructure.mq;

import com.sixbean.testpike.aiservice.gen.infrastructure.mq.AiMqDeadLetter;
import com.sixbean.testpike.common.mq.LinkageProposalRequestMessage;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Consumer-side topology declaration for the proposal-request relay queue.
 * <p>The queue itself is owned by the linkage ledger in core, but a consumer
 * that only passive-declares dies on a freshly provisioned broker when its
 * service boots before the producer (queue declare-passive answers NOT_FOUND
 * and the listener container treats a missing queue as fatal). Following the
 * convention of every other ai-service consumer family (ChatRunQueueConfig),
 * the consuming service declares its own queue too: the arguments here match
 * the core declaration byte-for-byte through the shared message/topology
 * constants, so the re-declaration is idempotent whichever side starts first.
 */
@Configuration
public class LinkageProposalRequestQueueConfig {

    /** Same literal as the ledger-side declaration (single source in common). */
    public static final String QUEUE_NAME = LinkageProposalRequestMessage.QUEUE_NAME;

    @Bean
    public Queue linkageProposalRequestConsumerQueue() {
        return QueueBuilder.durable(QUEUE_NAME)
                .deadLetterExchange(AiMqDeadLetter.DLX_EXCHANGE)
                .deadLetterRoutingKey(QUEUE_NAME)
                .build();
    }

    /** Dead-letter queue of the proposal-request leg (shared DLX in gen domain). */
    @Bean
    public Queue linkageProposalRequestDeadLetterQueue() {
        return QueueBuilder.durable(AiMqDeadLetter.dlqName(QUEUE_NAME)).build();
    }

    @Bean
    public Binding linkageProposalRequestDeadLetterBinding(Queue linkageProposalRequestDeadLetterQueue,
                                                           DirectExchange aiMqDeadLetterExchange) {
        return BindingBuilder.bind(linkageProposalRequestDeadLetterQueue).to(aiMqDeadLetterExchange)
                .with(QUEUE_NAME);
    }

    @Bean
    public Binding linkageProposalRequestConsumerBinding(Queue linkageProposalRequestConsumerQueue,
                                                         TopicExchange aiGenExchange) {
        return BindingBuilder.bind(linkageProposalRequestConsumerQueue).to(aiGenExchange)
                .with(LinkageProposalRequestMessage.ROUTING_PROPOSAL_REQUESTED);
    }
}
