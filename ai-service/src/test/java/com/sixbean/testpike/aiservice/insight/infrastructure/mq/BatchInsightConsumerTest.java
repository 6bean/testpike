package com.sixbean.testpike.aiservice.insight.infrastructure.mq;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.insight.domain.service.BatchInsightService;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.idempotent.IdempotentAspect;
import com.sixbean.testpike.common.idempotent.MqIdempotentConsumeSupport;
import com.sixbean.testpike.common.lock.RedisReentrantLock;
import com.sixbean.testpike.common.mq.BatchFinalizedEventMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * Batch-insight consumer idempotency test (AO-7 stage 2b; mirrors
 * ClassifyConsumerTest — real Redis 6379 + AspectJProxyFactory idempotent proxy +
 * counting stub).
 * <p>Dedup key = insight:batch:{batchId}: a redelivery of the same finalize event
 * (MQ at-least-once) clusters once; different batches each run. Consumer gate =
 * eventType EXEC_BATCH_FINALIZED ∧ batchId non-null ∧ projectId non-null (other
 * events share the testpike.events broadcast and are acked with zero action).
 * Business exceptions propagate for container retry → dead letter (never swallowed).
 */
class BatchInsightConsumerTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static final long BATCH_BASE = 961_000_000L + (System.nanoTime() % 20_000_000L);

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory("localhost", 6379);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (factory != null) {
            factory.destroy();
        }
    }

    private static class CountingInsightService implements BatchInsightService {
        final AtomicInteger calls = new AtomicInteger();
        RuntimeException failure;

        @Override
        public void cluster(BatchFinalizedEventMessage message) {
            if (failure != null) {
                throw failure;
            }
            calls.incrementAndGet();
        }
    }

    private BatchInsightConsumer harness(CountingInsightService service, RedisReentrantLock lock) {
        MqIdempotentConsumeSupport support = new MqIdempotentConsumeSupport(lock);
        AspectJProxyFactory pf = new AspectJProxyFactory(new BatchInsightIdempotentRunner(service));
        pf.addAspect(new IdempotentAspect(redis));
        pf.setProxyTargetClass(true);
        return new BatchInsightConsumer(pf.getProxy(), support, mock(CoreGateway.class));
    }

    private BatchFinalizedEventMessage message(String eventType, Long projectId, Long batchId) {
        return new BatchFinalizedEventMessage(eventType, null, projectId, null,
                null, null, null, null, null, 1_700_000_000_000L, null,
                null, null, null, null, batchId, 7);
    }

    @Test
    void sameBatchRedelivery_deduplicated_otherBatchAllowed() {
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        CountingInsightService service = new CountingInsightService();
        BatchInsightConsumer consumer = harness(service, lock);
        long batchId = BATCH_BASE + 1;

        consumer.onMessage(message("EXEC_BATCH_FINALIZED", 1L, batchId));
        consumer.onMessage(message("EXEC_BATCH_FINALIZED", 1L, batchId));   // redelivery → dedup
        assertEquals(1, service.calls.get(), "同 batchId 重投不得二次聚类");

        consumer.onMessage(message("EXEC_BATCH_FINALIZED", 1L, batchId + 1));   // another batch = new key, runs
        assertEquals(2, service.calls.get(), "不同批次=新幂等键，正常聚类");
    }

    @Test
    void gateMessages_wrongTypeOrMissingIds_zeroDispatch() {
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        CountingInsightService service = new CountingInsightService();
        BatchInsightConsumer consumer = harness(service, lock);

        consumer.onMessage(message("CASE_GENERATED", 1L, BATCH_BASE + 10));   // foreign event type = zero action
        consumer.onMessage(message("EXEC_BATCH_FINALIZED", null, BATCH_BASE + 11)); // missing project = zero action
        consumer.onMessage(message("EXEC_BATCH_FINALIZED", 1L, null));        // missing batch id = zero action
        assertEquals(0, service.calls.get(), "闸外消息零聚类动作");
    }

    @Test
    void serviceThrows_rethrowsForRetry_notSwallowed() {
        // Consumer-side exceptions propagate for container retry → dead letter
        // (ClassifyConsumer posture — never swallow silently).
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        CountingInsightService service = new CountingInsightService();
        service.failure = new ServiceException("core 写回失败", BizErrorCode.SERVICE_UNAVAILABLE);
        BatchInsightConsumer consumer = harness(service, lock);

        assertThrows(ServiceException.class,
                () -> consumer.onMessage(message("EXEC_BATCH_FINALIZED", 1L, BATCH_BASE + 20)));
    }
}
