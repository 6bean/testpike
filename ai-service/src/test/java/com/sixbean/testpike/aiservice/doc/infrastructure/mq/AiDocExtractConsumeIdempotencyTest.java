package com.sixbean.testpike.aiservice.doc.infrastructure.mq;

import com.sixbean.testpike.aiservice.testsupport.HostRedisSession;
import com.sixbean.testpike.aiservice.doc.domain.service.DocExtractService;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.common.idempotent.IdempotentAspect;
import com.sixbean.testpike.common.idempotent.MqIdempotentConsumeSupport;
import com.sixbean.testpike.common.lock.RedisReentrantLock;
import com.sixbean.testpike.common.mq.AiDocExtractMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;


class AiDocExtractConsumeIdempotencyTest {

    private static HostRedisSession session;
    private static StringRedisTemplate redis;
    private static final long DOC_BASE = 900_000_000L + (System.nanoTime() % 50_000_000L);

    @BeforeAll
    static void connect() { session = HostRedisSession.connect(); redis = session.template(); }

    @AfterAll
    static void disconnect() { if (session != null) session.close(); }

    private AiDocExtractConsumer harness(ExtractCallCounter service,
                                         RedisReentrantLock lock) {
        MqIdempotentConsumeSupport support = new MqIdempotentConsumeSupport(lock);
        AspectJProxyFactory pf = new AspectJProxyFactory(new AiDocExtractIdempotentRunner(service));
        pf.addAspect(new IdempotentAspect(redis));
        pf.setProxyTargetClass(true);
        return new AiDocExtractConsumer(pf.getProxy(), support,
                new CoreGateway("http://localhost:59999"));
    }

    private AiDocExtractMessage message(long documentId, String token) {
        return new AiDocExtractMessage(documentId, 1L, 1L, null, token);
    }

    @Test
    void sameGeneration_redeliveryDeduplicated_newGenerationAllowed() {
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        ExtractCallCounter service = new ExtractCallCounter();
        AiDocExtractConsumer consumer = harness(service, lock);
        long docId = DOC_BASE + 11;
        String token = UUID.randomUUID().toString();

        consumer.onMessage(message(docId, token));
        consumer.onMessage(message(docId, token));
        assertEquals(1, service.calls.get(), "同 documentId+extractToken 重投不得二次提取");

        consumer.onMessage(message(docId, UUID.randomUUID().toString()));
        assertEquals(2, service.calls.get(), "新 extractToken=新轮次，幂等键不得误拦重提取");
    }
}
