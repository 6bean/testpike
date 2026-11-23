package com.sixbean.testpike.aiservice.knowledge.infrastructure.mq;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.knowledge.domain.service.KnowledgeIngestService;
import com.sixbean.testpike.common.idempotent.IdempotentAspect;
import com.sixbean.testpike.common.idempotent.MqIdempotentConsumeSupport;
import com.sixbean.testpike.common.lock.RedisReentrantLock;
import com.sixbean.testpike.common.mq.AiKbIngestMessage;
import com.sixbean.testpike.common.mq.CaseIngestEventMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class CaseIngestConsumerTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static final long CASE_BASE = 950_000_000L + (System.nanoTime() % 20_000_000L);
    private static final long REQ_BASE = 960_000_000L + (System.nanoTime() % 20_000_000L);

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

    private static class CountingCaseIngestService implements KnowledgeIngestService {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger excellentCalls = new AtomicInteger();
        final AtomicInteger requirementCalls = new AtomicInteger();
        final AtomicInteger retireCalls = new AtomicInteger();

        @Override
        public void ingest(AiKbIngestMessage message) {
        }

        @Override
        public void ingestCase(CaseIngestEventMessage message) {
            calls.incrementAndGet();
        }

        @Override
        public void applyExcellentFlag(CaseIngestEventMessage message) {
            excellentCalls.incrementAndGet();
        }

        @Override
        public void ingestRequirement(CaseIngestEventMessage message) {
            requirementCalls.incrementAndGet();
        }

        @Override
        public void retireRequirement(long requirementId) {
            retireCalls.incrementAndGet();
        }

        @Override
        public void cleanupCase(long caseId) {
        }

        @Override
        public void cleanupProject(long projectId) {
        }
    }

    private CaseIngestConsumer harness(CountingCaseIngestService service, RedisReentrantLock lock) {
        return harness(service, lock, enabledGateway());
    }

    
    private static CoreGateway enabledGateway() {
        CoreGateway gateway = mock(CoreGateway.class);
        when(gateway.getProject(anyLong()))
                .thenReturn(new CoreGateway.ProjectBrief(1L, "P", "zh-CN", 1));
        return gateway;
    }

    private CaseIngestConsumer harness(CountingCaseIngestService service, RedisReentrantLock lock,
                                       CoreGateway gateway) {
        MqIdempotentConsumeSupport support = new MqIdempotentConsumeSupport(lock);
        AspectJProxyFactory pf = new AspectJProxyFactory(new CaseIngestIdempotentRunner(service));
        pf.addAspect(new IdempotentAspect(redis));
        pf.setProxyTargetClass(true);
        return new CaseIngestConsumer(pf.getProxy(), support, gateway,
                new com.sixbean.testpike.aiservice.knowledge.domain.component.ProjectKbGate(gateway));
    }

    private CaseIngestEventMessage message(Long caseId, Integer version, String action) {
        return new CaseIngestEventMessage("REVIEW_CHANGED", caseId, 1L, 1L, action,
                null, null, null, null, 1_700_000_000_000L, version);
    }

    
    private CaseIngestEventMessage requirementMessage(String eventType, Long requirementId,
                                                      Integer requirementVersion) {
        return new CaseIngestEventMessage(eventType, null, 1L, 1L, null, null, null, null, null,
                1_700_000_000_000L, null, requirementId, requirementVersion);
    }

    @Test
    void sameVersion_redeliveryDeduplicated_newVersionAllowed() {
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        CountingCaseIngestService service = new CountingCaseIngestService();
        CaseIngestConsumer consumer = harness(service, lock);
        long caseId = CASE_BASE + 7;

        consumer.onMessage(message(caseId, 3, "PASS"));
        consumer.onMessage(message(caseId, 3, "PASS"));
        assertEquals(1, service.calls.get(), "同 caseId+caseVersion 重投不得二次入库");

        consumer.onMessage(message(caseId, 4, "PASS"));
        assertEquals(2, service.calls.get(), "新版本=新幂等键，重入库正常放行（保最新已通过版）");
    }

    @Test
    void nonPassAction_zeroIngest() {
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        CountingCaseIngestService service = new CountingCaseIngestService();
        CaseIngestConsumer consumer = harness(service, lock);
        long caseId = CASE_BASE + 8;

        consumer.onMessage(message(caseId, 3, "NEED_CHANGE"));
        consumer.onMessage(message(caseId, 3, "REJECT"));
        assertEquals(0, service.calls.get(), "非已通过态零入库（TC-G1-01 入库闸 action=PASS）");
    }

    @Test
    void nullCaseIdOrProject_zeroIngest() {
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        CountingCaseIngestService service = new CountingCaseIngestService();
        CaseIngestConsumer consumer = harness(service, lock);

        consumer.onMessage(message(null, 3, "PASS"));
        consumer.onMessage(message(CASE_BASE + 9, 3, "PASS"));
        assertEquals(1, service.calls.get(), "caseId=null 事件被闸；正常单用例事件放行");
    }


    @Test
    void excellentAction_dispatchesFlagLeg_notIngest() {
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        CountingCaseIngestService service = new CountingCaseIngestService();
        CaseIngestConsumer consumer = harness(service, lock);
        long caseId = CASE_BASE + 11;

        consumer.onMessage(message(caseId, 3, "EXCELLENT"));

        assertEquals(1, service.excellentCalls.get(), "EXCELLENT 分派 applyExcellentFlag（TC-D3-03）");
        assertEquals(0, service.calls.get(), "标记链路禁触发重入库（不重入向量）");
    }

    @Test
    void passThenExcellent_sameVersion_bothDispatched() {
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        CountingCaseIngestService service = new CountingCaseIngestService();
        CaseIngestConsumer consumer = harness(service, lock);
        long caseId = CASE_BASE + 12;

        consumer.onMessage(message(caseId, 5, "PASS"));
        consumer.onMessage(message(caseId, 5, "EXCELLENT"));

        assertEquals(1, service.calls.get(), "PASS 链路入库一次");
        assertEquals(1, service.excellentCalls.get(), "同版本不同 action 分链路放行（键尾缀 action）");

        consumer.onMessage(message(caseId, 5, "EXCELLENT"));
        assertEquals(1, service.excellentCalls.get(), "EXCELLENT 重投幂等去重不变");
    }

    @Test
    void needChangeAfterExcellent_zeroDispatch() {
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        CountingCaseIngestService service = new CountingCaseIngestService();
        CaseIngestConsumer consumer = harness(service, lock);
        long caseId = CASE_BASE + 13;

        consumer.onMessage(message(caseId, 3, "NEED_CHANGE"));
        consumer.onMessage(message(caseId, 3, "REJECT"));

        assertEquals(0, service.calls.get());
        assertEquals(0, service.excellentCalls.get(), "评审回退动作零分派（TC-G1-01 闸口径不扩）");
    }


    @Test
    void kbDisabled_passMessage_skippedWithAuditTrail() {
        CoreGateway gateway = mock(CoreGateway.class);
        when(gateway.getProject(anyLong()))
                .thenReturn(new CoreGateway.ProjectBrief(1L, "P", "zh-CN", 0));
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        CountingCaseIngestService service = new CountingCaseIngestService();
        CaseIngestConsumer consumer = harness(service, lock, gateway);

        consumer.onMessage(message(CASE_BASE + 20, 3, "PASS"));

        assertEquals(0, service.calls.get(), "开关=关→PASS 自动入库豁免（TC-KBS-01）");
        verify(gateway).writeKbGateSkip(eq("kb-ingest:case:" + (CASE_BASE + 20)), eq(1L), eq(1L), any());
    }

    @Test
    void kbDisabled_excellentAction_stillDispatched() {
        CoreGateway gateway = mock(CoreGateway.class);
        when(gateway.getProject(anyLong()))
                .thenReturn(new CoreGateway.ProjectBrief(1L, "P", "zh-CN", 0));
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        CountingCaseIngestService service = new CountingCaseIngestService();
        CaseIngestConsumer consumer = harness(service, lock, gateway);

        consumer.onMessage(message(CASE_BASE + 21, 3, "EXCELLENT"));

        assertEquals(1, service.excellentCalls.get(), "Consumer 层不拦 EXCELLENT（分型归 Service）");
        verify(gateway, never()).writeKbGateSkip(any(), anyLong(), any(), any());
    }

    @Test
    void kbGateProjectQueryFails_rethrowsForRetry_notSilentSkip() {
        CoreGateway gateway = mock(CoreGateway.class);
        when(gateway.getProject(anyLong())).thenThrow(new IllegalStateException("core unavailable"));
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        CountingCaseIngestService service = new CountingCaseIngestService();
        CaseIngestConsumer consumer = harness(service, lock, gateway);

        assertThrows(IllegalStateException.class,
                () -> consumer.onMessage(message(CASE_BASE + 22, 3, "PASS")));
        assertEquals(0, service.calls.get(), "查询失败不得放行入库也不得静默跳过");
        verify(gateway, never()).writeKbGateSkip(any(), anyLong(), any(), any());
    }


    @Test
    void reviewPassedRequirement_ingestsOnce_sameVersionDedup_newVersionAllowed() {
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        CountingCaseIngestService service = new CountingCaseIngestService();
        CoreGateway gateway = enabledGateway();
        CaseIngestConsumer consumer = harness(service, lock, gateway);
        long requirementId = REQ_BASE + 30;

        consumer.onMessage(requirementMessage("REVIEW_PASSED", requirementId, 2));
        consumer.onMessage(requirementMessage("REVIEW_PASSED", requirementId, 2));
        assertEquals(1, service.requirementCalls.get(),
                "kb-ingest:req:{id}:{version} 同键重投不得二次入库");
        verify(gateway).writeDuplicateDrop(eq("kb-ingest:req:" + requirementId),
                eq(1L), eq(1L), any());

        consumer.onMessage(requirementMessage("REVIEW_PASSED", requirementId, 3));
        assertEquals(2, service.requirementCalls.get(),
                "新版本=新幂等键，重入库正常放行（保最新已通过版）");
        assertEquals(0, service.calls.get(), "需求事件禁走用例入库链路");
    }

    @Test
    void changedRequirement_retires_noIngestLeg() {
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        CountingCaseIngestService service = new CountingCaseIngestService();
        CaseIngestConsumer consumer = harness(service, lock);

        consumer.onMessage(requirementMessage("CHANGED", REQ_BASE + 31, 4));

        assertEquals(1, service.retireCalls.get(), "CHANGED→retireRequirement（台账 INACTIVE+向量删）");
        assertEquals(0, service.requirementCalls.get(), "CHANGED 禁触发入库链路动作");
    }

    @Test
    void changedThenReviewPassed_sameVersion_bothLegsRun_finalActive() {
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        CountingCaseIngestService service = new CountingCaseIngestService();
        CaseIngestConsumer consumer = harness(service, lock);
        long requirementId = REQ_BASE + 32;

        consumer.onMessage(requirementMessage("CHANGED", requirementId, 5));
        consumer.onMessage(requirementMessage("REVIEW_PASSED", requirementId, 5));

        assertEquals(1, service.retireCalls.get(), "CHANGED 下线一次");
        assertEquals(1, service.requirementCalls.get(),
                "同版本 REVIEW_PASSED 不得被 CHANGED 键吞（action 不入键≠跨动作共键）");
    }

    @Test
    void requirementKbDisabled_reviewPassed_skippedWithAuditTrail() {
        CoreGateway gateway = mock(CoreGateway.class);
        when(gateway.getProject(anyLong()))
                .thenReturn(new CoreGateway.ProjectBrief(1L, "P", "zh-CN", 0));
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        CountingCaseIngestService service = new CountingCaseIngestService();
        CaseIngestConsumer consumer = harness(service, lock, gateway);
        long requirementId = REQ_BASE + 33;

        consumer.onMessage(requirementMessage("REVIEW_PASSED", requirementId, 2));

        assertEquals(0, service.requirementCalls.get(), "开关=关→需求自动入库豁免（TC-KBS-01 同基径）");
        verify(gateway).writeKbGateSkip(eq("kb-ingest:req:" + requirementId), eq(1L), eq(1L), any());
    }

    @Test
    void requirementKbDisabled_changed_stillRetires() {
        CoreGateway gateway = mock(CoreGateway.class);
        when(gateway.getProject(anyLong()))
                .thenReturn(new CoreGateway.ProjectBrief(1L, "P", "zh-CN", 0));
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        CountingCaseIngestService service = new CountingCaseIngestService();
        CaseIngestConsumer consumer = harness(service, lock, gateway);

        consumer.onMessage(requirementMessage("CHANGED", REQ_BASE + 34, 2));

        assertEquals(1, service.retireCalls.get(), "关态 CHANGED 照下线（清理动作不受入库闸）");
        verify(gateway, never()).writeKbGateSkip(any(), anyLong(), any(), any());
    }

    @Test
    void requirementEvent_nullProjectId_zeroAction() {
        RedisReentrantLock lock = new RedisReentrantLock(redis);
        CountingCaseIngestService service = new CountingCaseIngestService();
        CaseIngestConsumer consumer = harness(service, lock);

        consumer.onMessage(new CaseIngestEventMessage("REVIEW_PASSED", null, null, 1L, null,
                null, null, null, null, 1_700_000_000_000L, null, REQ_BASE + 35, 2));

        assertEquals(0, service.requirementCalls.get(), "projectId=null 残缺需求事件被闸（H1 防御）");
        assertEquals(0, service.retireCalls.get(), "残缺事件零下线动作");
    }
}
