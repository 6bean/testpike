package com.sixbean.testpike.aiservice.doc.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F2 extraction task-level deadline value object .
 * <p>Contract pinned here: the window is parsed from the K8 seed with a fixed
 * default fallback, {@code fromConfig} anchors the deadline to an injected clock,
 * non-positive or unparsable raw values degrade to the seeded 7200s instead of
 * silently disabling the guard, and {@code clamp} either keeps the per-call
 * timeout, shrinks it to the remaining window (never below one second), or
 * fails fast with a human-readable AI_TIMEOUT once the window is spent.
 */
class ExtractDeadlineTest {

    @Test
    void fromConfig_正整数窗口_锚定注入时钟加窗口() {
        ExtractDeadline deadline = ExtractDeadline.fromConfig("120", 1_000L);
        assertEquals(1_000L + 120_000L, deadline.deadlineEpochMillis());
    }

    @Test
    void fromConfig_空值非数字或非正数_回退种子缺省7200s() {
        assertEquals(7_200_000L, ExtractDeadline.fromConfig(null, 0L).deadlineEpochMillis(),
                "键缺失回退缺省");
        assertEquals(7_200_000L, ExtractDeadline.fromConfig("abc", 0L).deadlineEpochMillis(),
                "非法值回退缺省");
        assertEquals(7_200_000L, ExtractDeadline.fromConfig("0", 0L).deadlineEpochMillis(),
                "0=非正数回退缺省（守卫永不静默关闭）");
        assertEquals(7_200_000L, ExtractDeadline.fromConfig("-5", 0L).deadlineEpochMillis(),
                "负数回退缺省");
        assertEquals(121_000L, ExtractDeadline.fromConfig("121", 0L).deadlineEpochMillis(),
                "任意正整数窗口原样生效");
    }

    @Test
    void clamp_剩余充足_原样返回单次超时() {
        ExtractDeadline deadline = new ExtractDeadline(System.currentTimeMillis() + 600_000L);
        assertEquals(Duration.ofSeconds(300), deadline.clamp(Duration.ofSeconds(300)),
                "窗口内不改单次超时（其余消费方行为零变化）");
    }

    @Test
    void clamp_剩余不足_钳到剩余量且不少于一秒() {
        ExtractDeadline tight = new ExtractDeadline(System.currentTimeMillis() + 3_000L);
        Duration clamped = tight.clamp(Duration.ofSeconds(300));
        assertTrue(clamped.toMillis() > 0 && clamped.toMillis() <= 3_000L,
                "钳制结果须贴近剩余量且不超单次超时, 实际=" + clamped);

        ExtractDeadline edge = new ExtractDeadline(System.currentTimeMillis() + 200L);
        assertEquals(Duration.ofSeconds(1), edge.clamp(Duration.ofSeconds(300)),
                "剩余不足一秒时托底 1s，禁下发零超时");
    }

    @Test
    void clamp_窗口耗尽_抛AI_TIMEOUT人话可重试() {
        ExtractDeadline spent = new ExtractDeadline(System.currentTimeMillis() - 1_000L);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> spent.clamp(Duration.ofSeconds(300)));
        assertEquals(BizErrorCode.AI_TIMEOUT, ex.getCode(),
                "错误码固定 AI_TIMEOUT，禁伪装成可重试的供应商异常");
        assertTrue(ex.getMessage().contains("时长上限"),
                "人话须点名超时原因, 实际=" + ex.getMessage());
    }

    /** Fake-clock seam (DD deadline hard stop): the window arithmetic is deterministic. */
    @Test
    void clamp_注入时钟_随推进逐段钳短并精确到点硬停() {
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong();
        ExtractDeadline deadline = new ExtractDeadline(500L, now::get);

        now.set(100L);
        assertEquals(Duration.ofSeconds(1), deadline.clamp(Duration.ofSeconds(300)),
                "剩余不足一秒仍托底 1s（T445 既有契约），且禁抛——正数窗口放行");

        now.set(500L);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> deadline.clamp(Duration.ofSeconds(300)));
        assertEquals(BizErrorCode.AI_TIMEOUT, ex.getCode(),
                "恰到达时限点=窗口耗尽硬停，禁再下发任何超时");
    }

    @Test
    void clamp_注入时钟_一秒托底对极短剩余依旧生效() {
        ExtractDeadline tight = new ExtractDeadline(1_000L, () -> 900L);
        assertEquals(Duration.ofSeconds(1), tight.clamp(Duration.ofSeconds(300)),
                "剩余 100ms>0：仍托底 1s（T445 既有契约，禁下发亚秒超时）");
    }
}
