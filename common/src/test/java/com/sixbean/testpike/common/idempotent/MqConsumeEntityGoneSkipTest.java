package com.sixbean.testpike.common.idempotent;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.EntityGoneSignals;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.lock.RedisReentrantLock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Entity-gone noise reduction on the consume guard (AJ batch ,
 * entity is already deleted, the guard must NOT rethrow (rethrow → container
 * retry storm → dead-letter row that can never succeed). It returns
 * ENTITY_GONE_SKIPPED so the consumer writes a skip audit and acks.
 * <p>L2 runs against the real host Redis 6379 — the outcome/lock semantics
 * under test are exactly the ones only real Redis can prove (same posture as
 * MqConsumeLeaseRenewalTest). Keys carry a per-run UUID prefix.
 */
class MqConsumeEntityGoneSkipTest {

    private static final RedisTestHarness HARNESS = new RedisTestHarness();
    private static StringRedisTemplate redis;
    private static final String RUN_ID = UUID.randomUUID().toString().substring(0, 8);

    @BeforeAll
    static void connect() {
        HARNESS.connect();
        redis = HARNESS.redis();
    }

    @AfterAll
    static void disconnect() {
        HARNESS.close();
    }

    private MqIdempotentConsumeSupport support() {
        return new MqIdempotentConsumeSupport(new RedisReentrantLock(redis));
    }

    // ---------- entity-gone ServiceException → ENTITY_GONE_SKIPPED, ack path ----------

    @Test
    void entityGoneFailure_returnsSkippedInsteadOfRethrow() {
        String key = "entity-gone-" + RUN_ID + ":1";
        String token = UUID.randomUUID().toString();
        ConsumeOutcome outcome = support().consumeOnce(key, token, () -> {
            throw new ServiceException("生成任务所属需求 8 已被删除，本次不执行", 50003);
        }, 60_000L);
        assertEquals(ConsumeOutcome.ENTITY_GONE_SKIPPED, outcome,
                "实体已删除类失败必须转为跳过裁决（降噪刀：禁上抛产生死信）");
    }

    // ---------- after a skipped entity-gone delivery the same key stays consumable ----------

    @Test
    void entityGoneSkipReleasesLock_nextDeliveryStillProcesses() {
        String key = "entity-gone-" + RUN_ID + ":2";
        support().consumeOnce(key, UUID.randomUUID().toString(), () -> {
            throw new ServiceException("文档 12 不存在", 50003);
        }, 60_000L);
        String token2 = UUID.randomUUID().toString();
        ConsumeOutcome second = support().consumeOnce(key, token2, () -> token2, 60_000L);
        assertEquals(ConsumeOutcome.PROCESSED, second,
                "跳过后消费锁必须已释放（否则后续投递被误判 DUPLICATE）");
    }

    // ---------- other business failures keep the old contract: rethrow → retry/DLQ ----------

    @Test
    void transientFailure_stillRethrowsForContainerRetry() {
        String key = "entity-gone-" + RUN_ID + ":3";
        assertThrows(ServiceException.class, () -> support().consumeOnce(key,
                UUID.randomUUID().toString(),
                () -> {
                    throw new ServiceException("provider connection refused", 50003);
                }, 60_000L),
                "非实体已删除的失败必须照旧上抛（有限重试后进死信，本刀零放宽）");
    }

    // ---------- vocabulary unit anchors (single source with the core classifier) ----------

    @Test
    void signalsMatchChineseBusinessWordingOnly() {
        assertEquals(true, EntityGoneSignals.matchesText("用例 77 不存在"));
        assertEquals(false, EntityGoneSignals.matchesText("batch HTTP 404 NOT FOUND"),
                "英文泛文案退出文本面（commit c1142ab49：provider 404 错误体同款 wording 会把真实失败吞成 entity-gone ack；英文形态改由结构化 BizErrorCode.NOT_FOUND 判定）");
        assertEquals(false, EntityGoneSignals.matchesText("sockettimeoutexception 超时"));
        assertEquals(false, EntityGoneSignals.matchesText(null));
    }

    @Test
    void signalsWalkCauseChainForWrappedFeignRejection() {
        ServiceException wrapped = new ServiceException("下游调用失败", 50003);
        wrapped.initCause(new ServiceException("requirement missing", BizErrorCode.NOT_FOUND));
        assertEquals(true, EntityGoneSignals.matches(wrapped),
                "Feign 包装层下的结构化 not-found 码也要命中（判定面=整条 cause 链，与 core 分类器同源）");
    }
}
