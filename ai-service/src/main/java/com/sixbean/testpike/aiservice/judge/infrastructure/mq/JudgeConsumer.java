package com.sixbean.testpike.aiservice.judge.infrastructure.mq;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.common.idempotent.ConsumeOutcome;
import com.sixbean.testpike.common.idempotent.MqIdempotentConsumeSupport;
import com.sixbean.testpike.common.mq.JudgeSampleMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;


@Component
public class JudgeConsumer {

    private static final Logger log = LoggerFactory.getLogger(JudgeConsumer.class);

    
    static final long CONSUME_LEASE_MILLIS = 10 * 60 * 1000L;

    private final JudgeIdempotentRunner idempotentRunner;
    private final MqIdempotentConsumeSupport consumeSupport;
    private final CoreGateway coreGateway;

    public JudgeConsumer(JudgeIdempotentRunner idempotentRunner,
                         MqIdempotentConsumeSupport consumeSupport,
                         CoreGateway coreGateway) {
        this.idempotentRunner = idempotentRunner;
        this.consumeSupport = consumeSupport;
        this.coreGateway = coreGateway;
    }

    @RabbitListener(queues = JudgeQueueConfig.QUEUE_NAME)
    public void onMessage(JudgeSampleMessage message) {
        log.info("裁判任务消费 runId={} sampleId={} llmConfigId={}",
                message.runId(), message.sampleId(), message.llmConfigId());
        String dedupKey = "ai-judge:" + message.runId() + ":" + message.sampleId();
        String token = UUID.randomUUID().toString();
        ConsumeOutcome outcome = consumeSupport.consumeOnce(dedupKey, token,
                () -> idempotentRunner.judge(message, token), CONSUME_LEASE_MILLIS);
        if (outcome == ConsumeOutcome.DUPLICATE) {
            log.info("裁判任务重复投递幂等跳过 runId={} sampleId={}",
                    message.runId(), message.sampleId());
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("runId", message.runId());
            before.put("sampleId", message.sampleId());
            before.put("evalsetDigest", message.evalsetDigest());
            coreGateway.writeDuplicateDrop(dedupKey, 0L, null, before);
        }
        // noise reduction: run/sample rows already deleted -> ack + skip audit
        if (outcome == ConsumeOutcome.ENTITY_GONE_SKIPPED) {
            log.info("裁判任务实体已删除降噪跳过 runId={}", message.runId());
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("runId", message.runId());
            before.put("sampleId", message.sampleId());
            before.put("evalsetDigest", message.evalsetDigest());
            coreGateway.writeEntityGoneSkip(dedupKey, 0L, null, before);
        }
    }
}
