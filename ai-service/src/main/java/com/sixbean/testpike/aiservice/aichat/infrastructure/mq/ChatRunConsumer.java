package com.sixbean.testpike.aiservice.aichat.infrastructure.mq;

import com.sixbean.testpike.aiservice.aichat.domain.service.ChatRunOrchestrator;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.common.idempotent.ConsumeOutcome;
import com.sixbean.testpike.common.idempotent.MqIdempotentConsumeSupport;
import com.sixbean.testpike.common.mq.AiChatRunMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * MQ consumer of dialog runs (AO batch step C of γ; route key
 * {@code ai.chat.run}, mirrors MockGenConsumer).
 * <p>Two-layer duplicate guard: the in-flight lock + message-id idempotency
 * key run the turn at most once, and a folded redelivery leaves a
 * duplicate_drop audit row (the operator sees why nothing happened). The
 * orchestrator journals its own provider failures as error frames, so
 * anything reaching the container retry here is a transport/ledger fault —
 * three tries, then the DLX of {@link ChatRunQueueConfig} keeps the run
 * replayable (the AJ batch operates dead letters from the ledger table).
 */
@Component
public class ChatRunConsumer {

    private static final Logger log = LoggerFactory.getLogger(ChatRunConsumer.class);

    /** A dialog turn holds one streaming ReAct loop; 15min lease matches the gen/doc domains. */
    static final long CONSUME_LEASE_MILLIS = 15 * 60 * 1000L;

    private final ChatRunIdempotentRunner idempotentRunner;
    private final MqIdempotentConsumeSupport consumeSupport;
    private final CoreGateway coreGateway;

    public ChatRunConsumer(ChatRunIdempotentRunner idempotentRunner,
                           MqIdempotentConsumeSupport consumeSupport,
                           CoreGateway coreGateway) {
        this.idempotentRunner = idempotentRunner;
        this.consumeSupport = consumeSupport;
        this.coreGateway = coreGateway;
    }

    @RabbitListener(queues = ChatRunQueueConfig.QUEUE_NAME)
    public void onMessage(AiChatRunMessage message) {
        log.info("Chat run task consumed sessionId={} messageId={} projectId={} userId={}",
                message.sessionId(), message.assistantMessageId(),
                message.projectId(), message.userId());
        String dedupKey = "ai-chat-run:" + message.assistantMessageId();
        String token = UUID.randomUUID().toString();
        ConsumeOutcome outcome = consumeSupport.consumeOnce(dedupKey, token,
                () -> idempotentRunner.run(message, token), CONSUME_LEASE_MILLIS);
        if (outcome == ConsumeOutcome.DUPLICATE) {
            log.info("Chat run task duplicate delivery skipped idempotently messageId={}", message.assistantMessageId());
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("sessionId", message.sessionId());
            before.put("assistantMessageId", message.assistantMessageId());
            coreGateway.writeDuplicateDrop(dedupKey, message.projectId(),
                    message.userId(), before);
        }
        // noise reduction: session/message already deleted -> ack + skip audit
        if (outcome == ConsumeOutcome.ENTITY_GONE_SKIPPED) {
            log.info("Chat run entity deleted, skipped for noise reduction messageId={}", message.assistantMessageId());
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("sessionId", message.sessionId());
            before.put("assistantMessageId", message.assistantMessageId());
            coreGateway.writeEntityGoneSkip(dedupKey, message.projectId(),
                    message.userId(), before);
        }
    }
}
