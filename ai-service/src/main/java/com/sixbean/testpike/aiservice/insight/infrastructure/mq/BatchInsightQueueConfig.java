package com.sixbean.testpike.aiservice.insight.infrastructure.mq;

import com.sixbean.testpike.aiservice.gen.infrastructure.mq.AiMqDeadLetter;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Batch-insight clustering queue configuration (AO-7 stage 2b; queue
 * testpike.ai-batch-insight consumes the core batch-finalized broadcast —
 * exchange testpike.events, routing key exec.batch.finalized, the same literal
 * as core CaseEventType.EXEC_BATCH_FINALIZED, single-sourced as a constant here).
 * <p>Three-bean shape mirrors ClassifyQueueConfig: durable queue + DLX with the
 * dead-letter routing key explicitly pinned to the queue name (prevents silent
 * loss from a mismatched DLQ binding), exchange reused from the gen domain.
 */
@Configuration
public class BatchInsightQueueConfig {

    /** Queue name (durable — a consumer crash must not drop the finalize event). */
    public static final String QUEUE_NAME = "testpike.ai-batch-insight";

    /** Routing key (core batch-finalized broadcast exec.batch.finalized single-sourced constant). */
    public static final String ROUTING_EXEC_BATCH_FINALIZED = "exec.batch.finalized";

    @Bean
    public Queue batchInsightQueue() {
        return QueueBuilder.durable(QUEUE_NAME)
                .deadLetterExchange(AiMqDeadLetter.DLX_EXCHANGE)
                // dead-letter routing key explicitly pinned to the queue name
                // (AiGen/CaseSample/Classify same posture — mismatched DLQ bindings drop silently)
                .deadLetterRoutingKey(QUEUE_NAME)
                .build();
    }

    /** Dead-letter queue (shares the aiMqDeadLetterExchange declaration bean, same posture). */
    @Bean
    public Queue batchInsightDeadLetterQueue() {
        return QueueBuilder.durable(AiMqDeadLetter.dlqName(QUEUE_NAME)).build();
    }

    @Bean
    public Binding batchInsightDeadLetterBinding(Queue batchInsightDeadLetterQueue,
                                                 DirectExchange aiMqDeadLetterExchange) {
        return BindingBuilder.bind(batchInsightDeadLetterQueue).to(aiMqDeadLetterExchange)
                .with(QUEUE_NAME);
    }

    /** Exchange reused from the gen domain declaration (testpike.events). */
    @Bean
    public Binding batchInsightBinding(Queue batchInsightQueue, TopicExchange aiGenExchange) {
        return BindingBuilder.bind(batchInsightQueue).to(aiGenExchange)
                .with(ROUTING_EXEC_BATCH_FINALIZED);
    }
}
