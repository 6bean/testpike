package com.sixbean.testpike.aiservice.doc.infrastructure.mq;

import com.sixbean.testpike.aiservice.doc.domain.service.DocExtractService;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.common.idempotent.ConsumeOutcome;
import com.sixbean.testpike.common.idempotent.ExtractDedupKeys;
import com.sixbean.testpike.common.idempotent.MqIdempotentConsumeSupport;
import com.sixbean.testpike.common.mq.AiDocExtractMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.context.RetryContextSupport;
import org.springframework.retry.support.RetrySynchronizationManager;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;


@Component
public class AiDocExtractConsumer {

    private static final Logger log = LoggerFactory.getLogger(AiDocExtractConsumer.class);

    /** Consume lock lease 15min (extraction chain LLM rounds worst-case minutes, same calibration as gen domain). */
    static final long CONSUME_LEASE_MILLIS = 15 * 60 * 1000L;

    /**
 * Renewal cadence = lease/3 : a run outliving
 * the raw lease would silently free the lock and let a redelivered copy
 * re-execute (double LLM spend); the watchdog keeps the lease alive while
 * the extraction is in flight, and crash self-healing keeps the original
 * 15min ceiling (dead process drops its watchdog, lock expires anyway).
 */
    static final long CONSUME_RENEW_INTERVAL_MILLIS = CONSUME_LEASE_MILLIS / 3;

    /**
 * K8 hot key sizing the idempotent in-flight marker
 * window in seconds. Factory default = 2x the consume lease (1800s): a
 * crashed holder's E: stamp self-heals shortly after the lease ceiling
 * instead of parking every redelivery as a duplicate for 24 hours. The
 * consume guard additionally takes over an orphan marker immediately when
 * the holder heartbeat is proven dead; this TTL is the backstop.
 */
    static final String INFLIGHT_TTL_CONFIG_KEY = "ai.doc.idempotent-inflight-seconds";
    static final long DEFAULT_INFLIGHT_SECONDS = 2 * CONSUME_LEASE_MILLIS / 1000L;

    /** Garbage/missing/non-positive config falls back to the derived default — the window is never 0. */
    static long resolveInflightSeconds(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return DEFAULT_INFLIGHT_SECONDS;
        }
        try {
            long parsed = Long.parseLong(rawValue.trim());
            return parsed > 0 ? parsed : DEFAULT_INFLIGHT_SECONDS;
        } catch (NumberFormatException ignore) {
            return DEFAULT_INFLIGHT_SECONDS;
        }
    }

    private final AiDocExtractIdempotentRunner idempotentRunner;
    private final MqIdempotentConsumeSupport consumeSupport;
    private final CoreGateway coreGateway;
    /**
     * DD batch item ㊀ terminal-failure seam (null = legacy assembly, the hook
     * stays disabled — same optional-seam posture as the runner's in-flight
     * registry above).
     */
    private final DocExtractService terminalWriteBack;
    /**
     * Container retry ceiling read from the SAME property the stateless retry
     * interceptor is built from (single source, no second spelling of "3");
     * the last attempt is the one whose rethrow sends the message to the
     * dead-letter exchange.
     */
    private final int maxAttempts;

    public AiDocExtractConsumer(AiDocExtractIdempotentRunner idempotentRunner,
                                MqIdempotentConsumeSupport consumeSupport,
                                CoreGateway coreGateway) {
        this(idempotentRunner, consumeSupport, coreGateway, null, 3);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AiDocExtractConsumer(AiDocExtractIdempotentRunner idempotentRunner,
                                MqIdempotentConsumeSupport consumeSupport,
                                CoreGateway coreGateway,
                                DocExtractService terminalWriteBack,
                                @Value("${spring.rabbitmq.listener.simple.retry.max-attempts:3}")
                                        int maxAttempts) {
        this.idempotentRunner = idempotentRunner;
        this.consumeSupport = consumeSupport;
        this.coreGateway = coreGateway;
        this.terminalWriteBack = terminalWriteBack;
        this.maxAttempts = maxAttempts;
    }

    @RabbitListener(queues = AiDocExtractQueueConfig.QUEUE_NAME)
    public void onMessage(AiDocExtractMessage message) {
        // dimensionHint logged end-to-end: proves the whitelist-validated value
        // survived core -> MQ -> ai-service without being dropped in transit.
        log.info("F2 提取任务消费 documentId={} projectId={} modelHint={} dimensionHint={}",
                message.documentId(), message.projectId(), message.modelHint(),
                message.dimensionHint());
        // ①: key grammar from ExtractDedupKeys (core probe and this
        // consumer share the single source — no second spelling of the layout)
        String dedupKey = ExtractDedupKeys.dedupKey(message.documentId(), message.extractToken());
        String token = UUID.randomUUID().toString();
        // K8 window read once per delivery (same posture as the deadline entry
        // read in DocExtractServiceImpl); garbage values fall back to 2x lease.
        long inflightSeconds = resolveInflightSeconds(
                coreGateway.getSysConfig(INFLIGHT_TTL_CONFIG_KEY));
        ConsumeOutcome outcome;
        try {
            outcome = consumeSupport.consumeOnce(dedupKey, token,
                    () -> idempotentRunner.run(message, token, inflightSeconds),
                    CONSUME_LEASE_MILLIS, CONSUME_RENEW_INTERVAL_MILLIS);
        } catch (Exception escapingFailure) {
            // DD batch item ㊀ (terminal-failure guarantee): an exception that
            // escapes the real execution on the LAST retry attempt goes to the
            // dead-letter exchange with this rethrow — without the hook, a
            // failure raised outside the service's own catch faces (pre-body
            // throws, idempotency/lock outages) would leave the document in
            // the extracting status forever. Scope stays narrow on purpose:
            // only the execution call is wrapped, so requeue paths and audit
            // outages never masquerade as a terminal loss. The write-back is
            // fail-soft and the original exception always re-throws intact.
            if (terminalWriteBack != null && isFinalAttempt()) {
                try {
                    terminalWriteBack.handleTerminalFailure(message, escapingFailure);
                } catch (Exception hookOutage) {
                    log.error("F2 提取终态回写钩子自身异常 documentId={}",
                            message.documentId(), hookOutage);
                }
            }
            throw escapingFailure;
        }
        if (outcome == ConsumeOutcome.DUPLICATE) {
            log.info("F2 提取任务重复投递幂等跳过 documentId={}", message.documentId());
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("documentId", message.documentId());
            before.put("extractToken", message.extractToken());
            coreGateway.writeDuplicateDrop(ExtractDedupKeys.lockKeyPrefix(message.documentId()),
                    message.projectId(), message.operatorId(), before);
        }
        // the consume lock is held but its holder's liveness
        // heartbeat died — that holder crashed without releasing. Acking this
        // redelivery (duplicate_drop or silence) would park the extraction
        // until the 15-minute lease expires with nobody working it, so the
        // delivery goes back to the queue instead. The zombie-lock spin stays
        // bounded by the lease ceiling; cross-redelivery throttling is a
        // topology decision pinned in the batch ledger, not a second back-off.
        if (outcome == ConsumeOutcome.HEARTBEAT_DEAD_REQUEUE) {
            log.warn("F2 提取任务重投遇到崩溃持有者（锁在途/心跳已死）→ 回队 documentId={} projectId={} extractToken={}",
                    message.documentId(), message.projectId(), message.extractToken());
            throw new ImmediateRequeueAmqpException(
                    "ai.doc.extract redelivery found the consume lock held by a crashed holder");
        }
        // noise reduction: target document already deleted -> ack + skip audit
        if (outcome == ConsumeOutcome.ENTITY_GONE_SKIPPED) {
            log.info("F2 提取实体已删除降噪跳过 documentId={}", message.documentId());
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("documentId", message.documentId());
            before.put("extractToken", message.extractToken());
            coreGateway.writeEntityGoneSkip(ExtractDedupKeys.lockKeyPrefix(message.documentId()),
                    message.projectId(), message.operatorId(), before);
        }
    }

    /**
     * Final-attempt probe over the stateless-retry context the listener
     * container maintains (attempt k reports k-1 failed retries). No context
     * = the retry interceptor is not engaged, so a throw here travels
     * straight to the dead-letter exchange and IS terminal; an unexpected
     * context shape is treated as terminal for the same reason — the
     * write-back is generation-guarded core-side, a redundant no-op always
     * beats a silently hung extracting row.
     */
    boolean isFinalAttempt() {
        if (!(RetrySynchronizationManager.getContext() instanceof RetryContextSupport support)) {
            return true;
        }
        return support.getRetryCount() >= maxAttempts - 1;
    }
}
