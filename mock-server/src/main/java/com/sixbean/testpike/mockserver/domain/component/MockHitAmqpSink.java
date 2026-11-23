package com.sixbean.testpike.mockserver.domain.component;

import com.sixbean.testpike.common.mq.MockHitBatchMessage;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * RabbitMQ adapter of the hit ledger batch sink : one
 * JSON message per buffered batch onto the shared event exchange with routing
 * key {@code mock.hit.batch}. The core consumer declares and binds the queue
 * — this plane only publishes. Transport failures surface as exceptions and
 * the recorder turns them into honest degradation, never silent loss.
 * <p>No @Component here on purpose: mock-server ships without a scan of this
 * package's wiring order assumptions; the bean is declared by the host
 * application configuration together with the recorder.
 */
public class MockHitAmqpSink implements MockHitBatchSink {

    /** Same topic exchange the review/assign event family already uses. */
    public static final String EXCHANGE = "testpike.events";
    /** Routing key of the hit ledger batch stream (core consumer binds it). */
    public static final String ROUTING_KEY = "mock.hit.batch";

    private final RabbitTemplate rabbitTemplate;

    public MockHitAmqpSink(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void send(MockHitBatchMessage batch) {
        rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, batch);
    }
}
