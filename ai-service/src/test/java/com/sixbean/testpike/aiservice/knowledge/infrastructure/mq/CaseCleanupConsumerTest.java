package com.sixbean.testpike.aiservice.knowledge.infrastructure.mq;

import com.sixbean.testpike.aiservice.testsupport.HostRedisSession;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.common.idempotent.IdempotentAspect;
import com.sixbean.testpike.common.idempotent.MqIdempotentConsumeSupport;
import com.sixbean.testpike.common.lock.RedisReentrantLock;
import com.sixbean.testpike.common.mq.CaseIngestEventMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;


class CaseCleanupConsumerTest {

    private static HostRedisSession session;
    private static StringRedisTemplate redis;
    private static final long CASE_BASE = 960_000_000L + (System.nanoTime() % 20_000_000L);
    private static final long PROJ_BASE = 961_000_000L + (System.nanoTime() % 20_000_000L);

    @BeforeAll
    static void connect() { session = HostRedisSession.connect(); redis = session.template(); }

    @AfterAll
    static void disconnect() { if (session != null) session.close(); }

    private CaseCleanupConsumer harness(CaseCleanupCountingService service) {
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        MqIdempotentConsumeSupport support = new MqIdempotentConsumeSupport(lock);
        AspectJProxyFactory pf = new AspectJProxyFactory(
                new CaseCleanupIdempotentRunner(service));
        pf.addAspect(new IdempotentAspect(redis));
        pf.setProxyTargetClass(true);
        return new CaseCleanupConsumer(pf.getProxy(), support,
                new CoreGateway("http://localhost:59999"));
    }

    private CaseIngestEventMessage event(String eventType, Long caseId, Long projectId,
                                         String action, long occurredAt) {
        return new CaseIngestEventMessage(eventType, caseId, projectId, 1L, action,
                null, null, null, null, occurredAt, null);
    }

    @Test
    void deletedEvent_triggersCleanupCase() {
        CaseCleanupCountingService service = new CaseCleanupCountingService();
        long caseId = CASE_BASE + 1;

        harness(service).onMessage(event("DELETED", caseId, PROJ_BASE, "DELETE",
                1_700_000_000_000L));

        assertEquals(1, service.cleanupCaseCalls.get(), "case.deleted → cleanupCase（TC-G1-04）");
        assertEquals(0, service.cleanupProjectCalls.get());
        assertEquals(0, service.ingestCalls.get(), "清理链路绝不误触入库");
    }

    @Test
    void invalidatedEvent_triggersCleanupCase() {
        CaseCleanupCountingService service = new CaseCleanupCountingService();
        long caseId = CASE_BASE + 2;

        harness(service).onMessage(event("INVALIDATED", caseId, PROJ_BASE, "EDIT_ROLLBACK",
                1_700_000_000_001L));

        assertEquals(1, service.cleanupCaseCalls.get(),
                "case.invalidated（编辑回退离开已通过）→ cleanupCase（14 §6.2 / TC-G1-03）");
    }

    @Test
    void projectInvalidatedEvent_triggersCleanupProject() {
        CaseCleanupCountingService service = new CaseCleanupCountingService();

        harness(service).onMessage(event("PROJECT_INVALIDATED", null, PROJ_BASE + 2,
                "ARCHIVE", 1_700_000_000_002L));

        assertEquals(1, service.cleanupProjectCalls.get(),
                "kb.project-invalidated（caseId=null）→ cleanupProject 批量清理");
        assertEquals(0, service.cleanupCaseCalls.get());
    }

    @Test
    void sameEvent_redeliveryDeduplicated_distinctEventsAllowed() {
        CaseCleanupCountingService service = new CaseCleanupCountingService();
        CaseCleanupConsumer consumer = harness(service);
        long caseId = CASE_BASE + 3;

        consumer.onMessage(event("DELETED", caseId, PROJ_BASE, "DELETE", 1_700_000_000_003L));
        consumer.onMessage(event("DELETED", caseId, PROJ_BASE, "DELETE", 1_700_000_000_003L));
        assertEquals(1, service.cleanupCaseCalls.get(), "同事件重投幂等去重");

        consumer.onMessage(event("INVALIDATED", caseId, PROJ_BASE, "EDIT_ROLLBACK",
                1_700_000_000_004L));
        assertEquals(2, service.cleanupCaseCalls.get(),
                "同用例新清理事件=新幂等键正常放行（纯 caseId 键会在重入库后吞掉再回退）");
    }

    @Test
    void ingestFamilyOrIncompleteEvent_zeroCleanup() {
        CaseCleanupCountingService service = new CaseCleanupCountingService();
        CaseCleanupConsumer consumer = harness(service);

        consumer.onMessage(event("REVIEW_CHANGED", CASE_BASE + 4, PROJ_BASE, "PASS",
                1_700_000_000_005L));
        consumer.onMessage(event("DELETED", null, PROJ_BASE, "DELETE",
                1_700_000_000_006L));
        consumer.onMessage(event("PROJECT_INVALIDATED", null, null, "ARCHIVE",
                1_700_000_000_007L));
        assertEquals(0, service.cleanupCaseCalls.get());
        assertEquals(0, service.cleanupProjectCalls.get());
    }

    @Test
    void nullMessage_noOp() {
        CaseCleanupCountingService service = new CaseCleanupCountingService();
        harness(service).onMessage(null);
        assertEquals(0, service.cleanupCaseCalls.get());
        assertEquals(0, service.cleanupProjectCalls.get());
        assertEquals(0, service.ingestCalls.get());
    }
}
