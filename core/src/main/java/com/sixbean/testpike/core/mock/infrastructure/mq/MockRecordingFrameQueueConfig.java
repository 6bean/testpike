package com.sixbean.testpike.core.mock.infrastructure.mq;

import com.sixbean.testpike.common.mq.MqDeadLetterTopology;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Recording frame queue : queue
 * {@code testpike.mock-recording-frame} bound to the shared event exchange
 * with routing key {@code mock.recording.frame} — the twin of the mock-server
 * frame sink. Failure topology mirrors the hit ledger queue (MockHitQueueConfig):
 * retry exhausted → DLQ with the routing key pinned to the queue name, where
 * the AJ replay surface picks it up.
 */
@Configuration
public class MockRecordingFrameQueueConfig {

    /** Queue name (durable — buffered frames must survive a core crash). */
    public static final String QUEUE_NAME = "testpike.mock-recording-frame";

    /** Routing key of the recording frame stream. */
    public static final String ROUTING_RECORDING_FRAME = "mock.recording.frame";

    @Bean
    public Queue mockRecordingFrameQueue() {
        return QueueBuilder.durable(QUEUE_NAME)
                .deadLetterExchange(MqDeadLetterTopology.DLX_EXCHANGE)
                .deadLetterRoutingKey(QUEUE_NAME)
                .build();
    }

    @Bean
    public Queue mockRecordingFrameDeadLetterQueue() {
        return QueueBuilder.durable(MqDeadLetterTopology.dlqName(QUEUE_NAME)).build();
    }

    @Bean
    public Binding mockRecordingFrameDeadLetterBinding(Queue mockRecordingFrameDeadLetterQueue,
                                                      org.springframework.amqp.core.DirectExchange mqDeadLetterExchange) {
        return BindingBuilder.bind(mockRecordingFrameDeadLetterQueue).to(mqDeadLetterExchange)
                .with(QUEUE_NAME);
    }

    /** Business binding (exchange bean from core RabbitEventConfig). */
    @Bean
    public Binding mockRecordingFrameBinding(Queue mockRecordingFrameQueue,
                                             TopicExchange caseEventExchange) {
        return BindingBuilder.bind(mockRecordingFrameQueue).to(caseEventExchange)
                .with(ROUTING_RECORDING_FRAME);
    }
}
