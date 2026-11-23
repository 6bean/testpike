package com.sixbean.testpike.aiservice.gen.infrastructure.mq;

import com.sixbean.testpike.aiservice.gen.domain.service.AiGenService;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.idempotent.IdempotentAspect;
import com.sixbean.testpike.common.idempotent.MqIdempotentConsumeSupport;
import com.sixbean.testpike.common.lock.RedisReentrantLock;
import com.sixbean.testpike.common.mq.AiGenMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


class AiGenConsumeIdempotencyTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static final long RUN_BASE = 900_000_000L + (System.nanoTime() % 50_000_000L);

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

    
    private static class CountingGenService implements AiGenService {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean failNext;
        volatile long sleepMillis;
        volatile String failMessage;

        @Override
        public void generate(AiGenMessage message) {
            if (sleepMillis > 0) {
                try {
                    Thread.sleep(sleepMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            calls.incrementAndGet();
            if (failMessage != null) {
                String msg = failMessage;
                failMessage = null;
                throw new ServiceException(msg, 50003);
            }
            if (failNext) {
                failNext = false;
                throw new ServiceException("模拟生成失败（LLM 下游异常）", 50003);
            }
        }
    }

    private record Harness(AiGenConsumer consumer, CountingGenService service,
                           RedisReentrantLock lock) {
    }

    private Harness harness() {
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        MqIdempotentConsumeSupport support = new MqIdempotentConsumeSupport(lock);
        CountingGenService service = new CountingGenService();
        AiGenIdempotentRunner runnerTarget = new AiGenIdempotentRunner(service);
        AspectJProxyFactory pf = new AspectJProxyFactory(runnerTarget);
        pf.addAspect(new IdempotentAspect(redis));
        pf.setProxyTargetClass(true);
        CoreGateway gateway = new CoreGateway("http://localhost:59999");
        return new Harness(new AiGenConsumer(pf.getProxy(), support, gateway), service, lock);
    }

    private AiGenMessage message(long taskId) {
        return new AiGenMessage(taskId, 1L, 1L, 1, "幂等夹具需求-" + UUID.randomUUID(), null, null);
    }


    @Test
    void duplicateDelivery_generatesOnlyOnce() {
        Harness h = harness();
        AiGenMessage m = message(RUN_BASE + 1);

        h.consumer().onMessage(m);
        h.consumer().onMessage(m);

        assertEquals(1, h.service().calls.get(), "重复投递不得二次生成（幂等跳过）");
    }


    @Test
    void restartGeneration_redeliveryReexecutes() {
        Harness h = harness();
        long taskId = RUN_BASE + 40;
        // first generation (genSeq 0) completes normally
        AiGenMessage first = new AiGenMessage(taskId, 1L, 1L, 1, "重启夹具需求", null, null, 0);
        h.consumer().onMessage(first);
        // exact replay of the same generation stays a duplicate
        h.consumer().onMessage(first);
        assertEquals(1, h.service().calls.get(), "同代数重投仍幂等");
        // a restart publishes the SAME taskId with the next genSeq -> must run
        AiGenMessage restart = new AiGenMessage(taskId, 1L, 1L, 1, "重启夹具需求", null, null, 1);
        h.consumer().onMessage(restart);
        assertEquals(2, h.service().calls.get(), "新代数（重启/重试）必须真执行，禁被首发完成账吞掉");
    }


    @Test
    void concurrentDelivery_onlyOneProcesses() throws Exception {
        Harness h = harness();
        h.service().sleepMillis = 400;
        AiGenMessage m = message(RUN_BASE + 2);
        AtomicInteger errors = new AtomicInteger();

        Runnable fire = () -> {
            try {
                h.consumer().onMessage(m);
            } catch (RuntimeException e) {
                errors.incrementAndGet();
            }
        };
        Thread a = new Thread(fire);
        Thread b = new Thread(fire);
        a.start();
        b.start();
        a.join(10_000);
        b.join(10_000);

        assertEquals(0, errors.get(), "并发重复方必须 ack 跳过，禁异常上抛（IT-MQ-01 禁重试风暴）");
        assertEquals(1, h.service().calls.get(), "在途双发仅一方处理（锁+幂等键双层）");
        assertEquals(0, h.lock().holdCount(
                MqIdempotentConsumeSupport.CONSUME_LOCK_PREFIX + "ai-gen:" + (RUN_BASE + 2) + ":1:0",
                "nobody"), "消费完成锁必须已释放（无泄漏）");
    }


    @Test
    void failureDoesNotCache_redeliveryReexecutes() {
        Harness h = harness();
        AiGenMessage m = message(RUN_BASE + 3);
        h.service().failNext = true;

        assertThrows(ServiceException.class, () -> h.consumer().onMessage(m));
        h.consumer().onMessage(m);

        assertEquals(2, h.service().calls.get(), "首发失败后重投必须可重新执行（失败不缓存）");
    }


    @Test
    void entityGoneFailure_isAckedWithoutRethrow() {
        Harness h = harness();
        AiGenMessage m = message(RUN_BASE + 4);
        h.service().failMessage = "生成任务所属需求 8 已被删除";

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> h.consumer().onMessage(m),
                "实体已删除类失败不得上抛（上抛=重试耗尽进死信，违降噪改谱）");
        assertEquals(1, h.service().calls.get(), "本切片只改失败去向，不改执行次数");
    }
}
