package com.sixbean.testpike.aiservice.knowledge.infrastructure.mq;

import com.sixbean.testpike.aiservice.testsupport.HostRedisSession;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.knowledge.domain.service.KnowledgeIngestService;
import com.sixbean.testpike.common.idempotent.IdempotentAspect;
import com.sixbean.testpike.common.idempotent.MqIdempotentConsumeSupport;
import com.sixbean.testpike.common.lock.RedisReentrantLock;
import com.sixbean.testpike.common.mq.AiKbIngestMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;


class KbIngestConsumerTest {

    private static HostRedisSession session;
    private static StringRedisTemplate redis;
    private static final long DOC_BASE = 970_000_000L + (System.nanoTime() % 20_000_000L);

    @BeforeAll
    static void connect() { session = HostRedisSession.connect(); redis = session.template(); }

    @AfterAll
    static void disconnect() { if (session != null) session.close(); }

    private static class CountingIngestService implements KnowledgeIngestService {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public void ingest(AiKbIngestMessage message) {
            calls.incrementAndGet();
        }

        @Override
        public void ingestCase(com.sixbean.testpike.common.mq.CaseIngestEventMessage message) {
        }

        @Override
        public void applyExcellentFlag(com.sixbean.testpike.common.mq.CaseIngestEventMessage message) {
        }

        @Override
        public void ingestRequirement(com.sixbean.testpike.common.mq.CaseIngestEventMessage message) {
        }

        @Override
        public void retireRequirement(long requirementId) {
        }

        @Override
        public void cleanupCase(long caseId) {
        }

        @Override
        public void cleanupProject(long projectId) {
        }
    }

    private KbIngestConsumer harness(CountingIngestService service, RedisReentrantLock lock) {
        MqIdempotentConsumeSupport support = new MqIdempotentConsumeSupport(lock);
        AspectJProxyFactory pf = new AspectJProxyFactory(new KbIngestIdempotentRunner(service));
        pf.addAspect(new IdempotentAspect(redis));
        pf.setProxyTargetClass(true);
        return new KbIngestConsumer(pf.getProxy(), support, new CoreGateway("http://localhost:59999"));
    }

    private AiKbIngestMessage message(long documentId, long occurredAt) {
        return new AiKbIngestMessage(documentId, 1L, 1L, occurredAt, null);
    }

    @Test
    void sameEvent_redeliveryDeduplicated_newOccurrenceAllowed() {
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        CountingIngestService service = new CountingIngestService();
        KbIngestConsumer consumer = harness(service, lock);
        long docId = DOC_BASE + 7;
        long occurredAt = 1_700_000_000_000L;

        consumer.onMessage(message(docId, occurredAt));
        consumer.onMessage(message(docId, occurredAt));
        assertEquals(1, service.calls.get(), "同 documentId+occurredAt 重投不得二次入库");

        consumer.onMessage(message(docId, occurredAt + 1));
        assertEquals(2, service.calls.get(), "新 occurredAt=新触发，幂等键不得误拦重入库");
    }
}
