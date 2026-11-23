package com.sixbean.testpike.core.mock.infrastructure.mq;

import com.sixbean.testpike.common.idempotent.ConsumeOutcome;
import com.sixbean.testpike.common.idempotent.MqIdempotentConsumeSupport;
import com.sixbean.testpike.common.mq.MockRecordingFrameBatchMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Core consumer of recording frame batches : the
 * mock-server ships masked proxy frames over {@code testpike.events} /
 * {@code mock.recording.frame}; this listener is the only path from the data
 * plane into the cassette box (the plane never touches PostgreSQL or Redis
 * directly).
 * <p>Double guard mirrors MockHitConsumer: in-flight lock + batchId idempotent
 * key — a requeue or DLQ replay of the same batch stages exactly once, so a
 * sealed cassette can never contain duplicated frames. Business exceptions
 * propagate (retry → dead letter) exactly like the hit consumer; a frame
 * batch for a ghost or sealed box is a visible backlog, never a silent drop.
 */
@Component
public class MockRecordingFrameConsumer {

    private static final Logger log = LoggerFactory.getLogger(MockRecordingFrameConsumer.class);

    /** One batch stages a handful of Redis pushes + one summary write: 5 min lease. */
    static final long CONSUME_LEASE_MILLIS = 5 * 60 * 1000L;

    private final MqIdempotentConsumeSupport consumeSupport;
    private final MockRecordingFrameIdempotentRunner idempotentRunner;

    public MockRecordingFrameConsumer(MqIdempotentConsumeSupport consumeSupport,
                                      MockRecordingFrameIdempotentRunner idempotentRunner) {
        this.consumeSupport = consumeSupport;
        this.idempotentRunner = idempotentRunner;
    }

    @RabbitListener(queues = MockRecordingFrameQueueConfig.QUEUE_NAME)
    public void onMessage(MockRecordingFrameBatchMessage message) {
        if (message == null || message.batchId() == null || message.batchId().isBlank()) {
            log.warn("Mock 录制帧批次缺 batchId——拒收进死信留痕，禁静默 ack");
            throw new IllegalArgumentException("frame batch without batchId cannot be deduplicated");
        }
        String dedupKey = "mock-rec-frame:" + message.batchId();
        String token = UUID.randomUUID().toString();
        ConsumeOutcome outcome = consumeSupport.consumeOnce(dedupKey, token,
                () -> idempotentRunner.run(message, token), CONSUME_LEASE_MILLIS);
        if (outcome == ConsumeOutcome.DUPLICATE) {
            log.info("Mock 录制帧重复投递幂等跳过 batchId={}", message.batchId());
        }
    }
}
