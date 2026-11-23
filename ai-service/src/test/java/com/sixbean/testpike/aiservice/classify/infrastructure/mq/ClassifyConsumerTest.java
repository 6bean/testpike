package com.sixbean.testpike.aiservice.classify.infrastructure.mq;

import com.sixbean.testpike.aiservice.testsupport.HostRedisSession;
import com.sixbean.testpike.aiservice.classify.domain.service.FailureClassifyService;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.idempotent.IdempotentAspect;
import com.sixbean.testpike.common.idempotent.MqIdempotentConsumeSupport;
import com.sixbean.testpike.common.lock.RedisReentrantLock;
import com.sixbean.testpike.common.mq.ExecFailedEventMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;


class ClassifyConsumerTest {

    private static HostRedisSession session;
    private static StringRedisTemplate redis;
    private static final long TASK_BASE = 958_000_000L + (System.nanoTime() % 20_000_000L);

    @BeforeAll
    static void connect() { session = HostRedisSession.connect(); redis = session.template(); }

    @AfterAll
    static void disconnect() { if (session != null) session.close(); }

    private static class CountingClassifyService implements FailureClassifyService {
        final AtomicInteger calls = new AtomicInteger();
        RuntimeException failure;

        @Override
        public void classify(ExecFailedEventMessage message) {
            if (failure != null) {
                throw failure;
            }
            calls.incrementAndGet();
        }
    }

    private ClassifyConsumer harness(CountingClassifyService service, RedisReentrantLock lock) {
        MqIdempotentConsumeSupport support = new MqIdempotentConsumeSupport(lock);
        AspectJProxyFactory pf = new AspectJProxyFactory(new ClassifyIdempotentRunner(service));
        pf.addAspect(new IdempotentAspect(redis));
        pf.setProxyTargetClass(true);
        return new ClassifyConsumer(pf.getProxy(), support, mock(CoreGateway.class));
    }

    private ExecFailedEventMessage message(Long caseId, Long projectId, Long taskId) {
        return new ExecFailedEventMessage("EXEC_TASK_FAILED", caseId, projectId, null,
                null, null, null, null, null, 1_700_000_000_000L, null,
                taskId, 2, "元素未找到：#submit-btn", null);
    }

    @Test
    void sameTaskRedelivery_deduplicated_newTaskAllowed() {
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        CountingClassifyService service = new CountingClassifyService();
        ClassifyConsumer consumer = harness(service, lock);
        long taskId = TASK_BASE + 1;

        consumer.onMessage(message(1L, 1L, taskId));
        consumer.onMessage(message(1L, 1L, taskId));
        assertEquals(1, service.calls.get(), "同 execTaskId 重投不得二次归因");

        consumer.onMessage(message(1L, 1L, taskId + 1));
        assertEquals(2, service.calls.get(), "不同任务=新幂等键，正常归因");
    }

    @Test
    void gateMessages_missingTaskOrProject_zeroDispatch() {
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        CountingClassifyService service = new CountingClassifyService();
        ClassifyConsumer consumer = harness(service, lock);

        consumer.onMessage(message(1L, null, TASK_BASE + 10));
        consumer.onMessage(message(1L, 1L, null));
        assertEquals(0, service.calls.get(), "闸外消息零归因动作");
    }

    @Test
    void serviceThrows_rethrowsForRetry_notSwallowed() {
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        CountingClassifyService service = new CountingClassifyService();
        service.failure = new ServiceException("core 写回失败", BizErrorCode.SERVICE_UNAVAILABLE);
        ClassifyConsumer consumer = harness(service, lock);

        assertThrows(ServiceException.class,
                () -> consumer.onMessage(message(1L, 1L, TASK_BASE + 20)));
    }
}
