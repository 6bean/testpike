package com.sixbean.testpike.aiservice.explore.infrastructure.mq;

import com.sixbean.testpike.aiservice.explore.domain.service.ExploreRunService;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.common.idempotent.IdempotentAspect;
import com.sixbean.testpike.common.idempotent.MqIdempotentConsumeSupport;
import com.sixbean.testpike.common.lock.RedisReentrantLock;
import com.sixbean.testpike.common.mq.ExploreRunMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;


class ExploreConsumeIdempotencyTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static final long SESSION_BASE = 950_000_000L + (System.nanoTime() % 50_000_000L);

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

    private static class CountingExploreRunService implements ExploreRunService {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public void run(ExploreRunMessage message) {
            calls.incrementAndGet();
        }
    }

    private ExploreConsumer harness(CountingExploreRunService service,
                                    RedisReentrantLock lock) {
        MqIdempotentConsumeSupport support = new MqIdempotentConsumeSupport(lock);
        AspectJProxyFactory pf = new AspectJProxyFactory(new ExploreIdempotentRunner(service));
        pf.addAspect(new IdempotentAspect(redis));
        pf.setProxyTargetClass(true);
        return new ExploreConsumer(pf.getProxy(), support,
                new CoreGateway("http://localhost:59999"));
    }

    @Test
    void sameSession_redeliveryDeduplicated_newSessionAllowed() {
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        CountingExploreRunService service = new CountingExploreRunService();
        ExploreConsumer consumer = harness(service, lock);
        long sessionId = SESSION_BASE + 21;

        consumer.onMessage(new ExploreRunMessage(sessionId, 1L, 7L));
        consumer.onMessage(new ExploreRunMessage(sessionId, 1L, 7L));
        assertEquals(1, service.calls.get(), "同 sessionId 重投不得二次探索（IT-MQ-01）");

        consumer.onMessage(new ExploreRunMessage(sessionId + 1, 1L, 7L));
        assertEquals(2, service.calls.get(), "新 sessionId=新幂等键，不得误拦并行会话");
    }
}
