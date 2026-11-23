package com.sixbean.testpike.aiservice.mock.infrastructure.mq;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.common.idempotent.ConsumeOutcome;
import com.sixbean.testpike.common.idempotent.MqIdempotentConsumeSupport;
import com.sixbean.testpike.common.mq.MockGenMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * MQ consumer of AI mock-drafting jobs ; route key
 * {@code ai.mock.gen}, mirrors SpecExtractConsumer).
 * <p>Two-layer duplicate guard: the in-flight lock + jobId/publishToken
 * idempotency key run the job at most once per generation, and a folded
 * redelivery leaves a duplicate_drop audit row (the operator sees why nothing
 * happened). The drafting service swallows its own failures into a terminal
 * 'failed' seal, so anything reaching the container retry here is a
 * transport/infrastructure fault — three tries, then the DLX of
 * {@link MockGenQueueConfig} keeps the message replayable.
 */
@Component
public class MockGenConsumer {

    private static final Logger log = LoggerFactory.getLogger(MockGenConsumer.class);

    /** Drafting holds one LLM round-trip plus an upload; 15min lease matches the doc/spec domains. */
    static final long CONSUME_LEASE_MILLIS = 15 * 60 * 1000L;

    private final MockGenIdempotentRunner idempotentRunner;
    private final MqIdempotentConsumeSupport consumeSupport;
    private final CoreGateway coreGateway;

    public MockGenConsumer(MockGenIdempotentRunner idempotentRunner,
                           MqIdempotentConsumeSupport consumeSupport,
                           CoreGateway coreGateway) {
        this.idempotentRunner = idempotentRunner;
        this.consumeSupport = consumeSupport;
        this.coreGateway = coreGateway;
    }

    @RabbitListener(queues = MockGenQueueConfig.QUEUE_NAME)
    public void onMessage(MockGenMessage message) {
        log.info("AI mock 起草任务消费 jobId={} spaceId={} projectId={} jobType={}",
                message.jobId(), message.spaceId(), message.projectId(), message.jobType());
        String dedupKey = "ai-mock-gen:" + message.jobId() + ":" + message.publishToken();
        String token = UUID.randomUUID().toString();
        ConsumeOutcome outcome = consumeSupport.consumeOnce(dedupKey, token,
                () -> idempotentRunner.run(message, token), CONSUME_LEASE_MILLIS);
        if (outcome == ConsumeOutcome.DUPLICATE) {
            log.info("AI mock 起草任务重复投递幂等跳过 jobId={}", message.jobId());
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("jobId", message.jobId());
            before.put("publishToken", message.publishToken());
            coreGateway.writeDuplicateDrop("ai-mock-gen:" + message.jobId(),
                    message.projectId(), message.operatorId(), before);
        }
        // noise reduction: mock job already deleted -> ack + skip audit
        if (outcome == ConsumeOutcome.ENTITY_GONE_SKIPPED) {
            log.info("AI mock 起草实体已删除降噪跳过 jobId={}", message.jobId());
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("jobId", message.jobId());
            before.put("publishToken", message.publishToken());
            coreGateway.writeEntityGoneSkip("ai-mock-gen:" + message.jobId(),
                    message.projectId(), message.operatorId(), before);
        }
    }
}
