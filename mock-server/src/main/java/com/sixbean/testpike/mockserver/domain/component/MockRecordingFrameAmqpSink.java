package com.sixbean.testpike.mockserver.domain.component;

import com.sixbean.testpike.common.mq.MockRecordingFrameBatchMessage;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * RabbitMQ adapter of the recording-frame batch sink : one
 * JSON message per buffered batch onto the shared event exchange with routing
 * key {@code mock.recording.frame}. Mirrors {@link MockHitAmqpSink}: the core
 * consumer declares and binds the queue, this plane only publishes, and
 * transport failures surface as exceptions for the recorder's honest
 * requeue-and-degrade path. No @Component on purpose (same wiring rationale
 * as MockHitAmqpSink: the host application declares the bean together with
 * the recorder).
 */
public class MockRecordingFrameAmqpSink implements MockRecordingFrameBatchSink {

    /** Same topic exchange the hit ledger and event family already use. */
    public static final String EXCHANGE = "testpike.events";
    /** Routing key of the recording-frame stream (core consumer binds it). */
    public static final String ROUTING_KEY = "mock.recording.frame";

    private final RabbitTemplate rabbitTemplate;

    public MockRecordingFrameAmqpSink(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void send(MockRecordingFrameBatchMessage batch) {
        rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, batch);
    }
}
