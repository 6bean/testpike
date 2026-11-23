package com.sixbean.testpike.aiservice.linkage.infrastructure.mq;

import com.sixbean.testpike.aiservice.linkage.domain.service.LinkageProposalDraftService;
import com.sixbean.testpike.aiservice.testsupport.HostRedisSession;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.idempotent.IdempotentAspect;
import com.sixbean.testpike.common.idempotent.MqIdempotentConsumeSupport;
import com.sixbean.testpike.common.lock.RedisReentrantLock;
import com.sixbean.testpike.common.mq.LinkageProposalRequestMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * L2 for the AI-side proposal consumer ; ClassifyConsumerTest
 * precedent — real host Redis + AspectJProxyFactory idempotent proxy +
 * counting service double, locks and idempotency keys are never faked).
 * Contracts pinned: same impact+version redelivery answers once; a re-armed
 * impact under a NEW upstream version is a new key and answers again;
 * member pairs without a producer perform zero action; an anchorless request
 * goes to the dead letter instead of a silent ack; business failures rethrow
 * for container retry.
 */
class LinkageProposalConsumerTest {

    private static HostRedisSession session;
    private static StringRedisTemplate redis;
    private static final long IMPACT_BASE =
            968_000_000L + (System.nanoTime() % 20_000_000L);

    @BeforeAll
    static void connect() {
        session = HostRedisSession.connect();
        redis = session.template();
    }

    @AfterAll
    static void disconnect() {
        if (session != null) {
            session.close();
        }
    }

    private static class CountingDraftService implements LinkageProposalDraftService {
        final AtomicInteger calls = new AtomicInteger();
        RuntimeException failure;

        @Override
        public void answer(LinkageProposalRequestMessage message) {
            if (failure != null) {
                throw failure;
            }
            calls.incrementAndGet();
        }
    }

    private LinkageProposalConsumer harness(CountingDraftService service) {
        MqIdempotentConsumeSupport support =
                new MqIdempotentConsumeSupport(new RedisReentrantLock(redis));
        AspectJProxyFactory pf = new AspectJProxyFactory(
                new LinkageProposalIdempotentRunner(service));
        pf.addAspect(new IdempotentAspect(redis));
        pf.setProxyTargetClass(true);
        return new LinkageProposalConsumer(pf.getProxy(), support);
    }

    private static LinkageProposalRequestMessage request(long impactId, Integer version,
                                                         String upstreamType, String downstreamType) {
        return new LinkageProposalRequestMessage(impactId, "case->" + downstreamType,
                upstreamType, 4100L, version, downstreamType, 4101L, 1L,
                1_700_000_000_000L);
    }

    @Test
    void sameImpactVersionRedelivery_deduplicated_newVersionAllowed() {
        CountingDraftService service = new CountingDraftService();
        LinkageProposalConsumer consumer = harness(service);
        long impactId = IMPACT_BASE + 1;

        consumer.onMessage(request(impactId, 3, "case", "script"));
        consumer.onMessage(request(impactId, 3, "case", "script"));
        assertEquals(1, service.calls.get(), "同 impact+版本 重投不得二次生成草案");

        consumer.onMessage(request(impactId, 4, "case", "script"));
        assertEquals(2, service.calls.get(), "新版本再触发=新幂等键，应再次生成");
    }

    @Test
    void memberWithoutProducer_zeroAction() {
        CountingDraftService service = new CountingDraftService();
        LinkageProposalConsumer consumer = harness(service);

        consumer.onMessage(request(IMPACT_BASE + 10, 1, "case", "appauto_stub"));
        consumer.onMessage(request(IMPACT_BASE + 11, 1, "plan", "script"));
        assertEquals(0, service.calls.get(), "非 (case→script) 成员对=零动作，账本保持 pending");
    }

    @Test
    void anchorlessRequest_rejectsToDeadLetter_neverSilentAck() {
        CountingDraftService service = new CountingDraftService();
        LinkageProposalConsumer consumer = harness(service);

        assertThrows(IllegalArgumentException.class, () -> consumer.onMessage(
                new LinkageProposalRequestMessage(null, "case->script", "case", 1L, 1,
                        "script", 2L, 1L, 1_700_000_000_000L)));
        assertThrows(IllegalArgumentException.class, () -> consumer.onMessage(
                new LinkageProposalRequestMessage(IMPACT_BASE + 20, "case->script", "case", 1L, 1,
                        "script", null, 1L, 1_700_000_000_000L)));
        assertEquals(0, service.calls.get(), "残缺请求不得触达生成链路");
    }

    @Test
    void serviceThrows_rethrowsForRetry_notSwallowed() {
        CountingDraftService service = new CountingDraftService();
        service.failure = new ServiceException("core 快照写回失败", BizErrorCode.SERVICE_UNAVAILABLE);
        LinkageProposalConsumer consumer = harness(service);

        assertThrows(ServiceException.class, () -> consumer.onMessage(
                request(IMPACT_BASE + 30, 1, "case", "script")));
    }
}
