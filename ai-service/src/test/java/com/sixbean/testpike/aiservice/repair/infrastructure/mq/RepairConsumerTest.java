package com.sixbean.testpike.aiservice.repair.infrastructure.mq;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.repair.domain.service.ScriptRepairLoopService;
import com.sixbean.testpike.common.idempotent.IdempotentAspect;
import com.sixbean.testpike.common.idempotent.MqIdempotentConsumeSupport;
import com.sixbean.testpike.common.lock.RedisReentrantLock;
import com.sixbean.testpike.common.mq.ExecFailedEventMessage;
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


class RepairConsumerTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static final long TASK_BASE = 973_000_000L + (System.nanoTime() % 20_000_000L);

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

    private static class CountingRepairService implements ScriptRepairLoopService {
        final AtomicInteger calls = new AtomicInteger();
        RuntimeException failure;

        @Override
        public void repair(ExecFailedEventMessage message) {
            if (failure != null) {
                throw failure;
            }
            calls.incrementAndGet();
        }
    }

    private RepairConsumer harness(CountingRepairService service, RedisReentrantLock lock) {
        MqIdempotentConsumeSupport support = new MqIdempotentConsumeSupport(lock);
        AspectJProxyFactory pf = new AspectJProxyFactory(new RepairIdempotentRunner(service));
        pf.addAspect(new IdempotentAspect(redis));
        pf.setProxyTargetClass(true);
        return new RepairConsumer(pf.getProxy(), support, mock(CoreGateway.class));
    }

    private ExecFailedEventMessage message(Long caseId, Long projectId, Long taskId) {
        return new ExecFailedEventMessage("EXEC_TASK_FAILED", caseId, projectId, null,
                null, null, null, null, null, 1_700_000_000_000L, null,
                taskId, 2, "元素未找到：#submit-btn", null);
    }

    @Test
    void sameTaskRedelivery_deduplicated_newTaskAllowed() {
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        CountingRepairService service = new CountingRepairService();
        RepairConsumer consumer = harness(service, lock);
        long taskId = TASK_BASE + 1;

        consumer.onMessage(message(1L, 1L, taskId));
        consumer.onMessage(message(1L, 1L, taskId));
        assertEquals(1, service.calls.get(), "同 execTaskId 重投不得二次进修复环");

        consumer.onMessage(message(1L, 1L, taskId + 1));
        assertEquals(2, service.calls.get(), "不同任务=新幂等键，正常进环");
    }

    @Test
    void gateMessages_missingTaskOrProject_zeroDispatch() {
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        CountingRepairService service = new CountingRepairService();
        RepairConsumer consumer = harness(service, lock);

        consumer.onMessage(message(1L, null, TASK_BASE + 10));
        consumer.onMessage(message(1L, 1L, null));
        consumer.onMessage(null);
        assertEquals(0, service.calls.get(), "闸外消息零动作");
    }

    @Test
    void businessException_propagatesForRetry() {
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        CountingRepairService service = new CountingRepairService();
        service.failure = new IllegalStateException("core 写路径抖动");
        RepairConsumer consumer = harness(service, lock);

        assertThrows(IllegalStateException.class,
                () -> consumer.onMessage(message(1L, 1L, TASK_BASE + 20)),
                "业务异常必须上抛交容器重试→死信（禁吞）");
    }
}
