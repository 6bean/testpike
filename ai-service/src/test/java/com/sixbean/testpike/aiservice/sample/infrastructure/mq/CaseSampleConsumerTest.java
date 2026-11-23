package com.sixbean.testpike.aiservice.sample.infrastructure.mq;

import com.sixbean.testpike.aiservice.testsupport.HostRedisSession;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.sample.domain.service.TrainingSampleService;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.idempotent.IdempotentAspect;
import com.sixbean.testpike.common.idempotent.MqIdempotentConsumeSupport;
import com.sixbean.testpike.common.lock.RedisReentrantLock;
import com.sixbean.testpike.common.mq.CaseIngestEventMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;


class CaseSampleConsumerTest {

    private static HostRedisSession session;
    private static StringRedisTemplate redis;
    private static final long CASE_BASE = 949_000_000L + (System.nanoTime() % 20_000_000L);

    @BeforeAll
    static void connect() { session = HostRedisSession.connect(); redis = session.template(); }

    @AfterAll
    static void disconnect() { if (session != null) session.close(); }

    private static class CountingSampleService implements TrainingSampleService {
        final AtomicInteger calls = new AtomicInteger();
        RuntimeException failure;

        @Override
        public void captureSample(CaseIngestEventMessage message) {
            if (failure != null) {
                throw failure;
            }
            calls.incrementAndGet();
        }
    }

    private CaseSampleConsumer harness(CountingSampleService service, RedisReentrantLock lock) {
        MqIdempotentConsumeSupport support = new MqIdempotentConsumeSupport(lock);
        AspectJProxyFactory pf = new AspectJProxyFactory(new CaseSampleIdempotentRunner(service));
        pf.addAspect(new IdempotentAspect(redis));
        pf.setProxyTargetClass(true);
        return new CaseSampleConsumer(pf.getProxy(), support, mock(CoreGateway.class));
    }

    private CaseIngestEventMessage message(Long caseId, Integer version, String action) {
        return new CaseIngestEventMessage("REVIEW_CHANGED", caseId, 1L, 1L, action,
                null, null, null, null, 1_700_000_000_000L, version);
    }

    @Test
    void sameVersionRedelivery_deduplicated_newVersionAllowed() {
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        CountingSampleService service = new CountingSampleService();
        CaseSampleConsumer consumer = harness(service, lock);
        long caseId = CASE_BASE + 1;

        consumer.onMessage(message(caseId, 3, "REJECT"));
        consumer.onMessage(message(caseId, 3, "REJECT"));
        assertEquals(1, service.calls.get(), "同 caseId+caseVersion+action 重投不得二次沉淀");

        consumer.onMessage(message(caseId, 5, "REJECT"));
        assertEquals(2, service.calls.get(), "新版本=新幂等键，轮次样本正常放行");
    }

    @Test
    void gateActions_onlyRejectAndPass_dispatched() {
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        CountingSampleService service = new CountingSampleService();
        CaseSampleConsumer consumer = harness(service, lock);
        long caseId = CASE_BASE + 2;

        consumer.onMessage(message(caseId, 3, "NEED_CHANGE"));
        consumer.onMessage(message(caseId, 3, "EXCELLENT"));
        consumer.onMessage(message(null, 3, "REJECT"));
        assertEquals(0, service.calls.get(), "非 {REJECT,PASS} 与批量摘要零沉淀");

        consumer.onMessage(message(caseId, 4, "PASS"));
        assertEquals(1, service.calls.get(), "PASS 放行（需修改史判定归 Service 链路）");
    }

    @Test
    void serviceThrows_rethrowsForRetry_notSwallowed() {
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        CountingSampleService service = new CountingSampleService();
        service.failure = new ServiceException("core 回查失败", BizErrorCode.SERVICE_UNAVAILABLE);
        CaseSampleConsumer consumer = harness(service, lock);

        assertThrows(ServiceException.class,
                () -> consumer.onMessage(message(CASE_BASE + 3, 2, "REJECT")));
    }
}
