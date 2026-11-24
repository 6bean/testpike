package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.aiservice.doc.domain.component.ExtractDeadline;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task-level deadline hard stop across the whole retry loop (DD batch ,
 * sibling of the segment that clamped only per endpoint).
 * <p>Pinned here: every retry attempt re-reads the remaining window so the
 * per-attempt timeout is min(resolved K8 timeout, remaining); once the window
 * is spent the retry policy aborts immediately through the attempt gate
 * (no backoff burn, no further provider call, no degradation attempt);
 * a null clamp keeps the legacy loop byte-for-byte (other callers zero drift);
 * the fake clock proves the three-retry wall never exceeds the deadline.
 * <p>Infra (factory/router/gateway) is mocked per L2 rules; the chain loop,
 * retry policy and deadline component all run real code.
 */
class LlmFallbackChainDeadlineHardStopTest {

    private static final long PROJECT_ID = 3L;

    private final LlmProviderFactory factory = mock(LlmProviderFactory.class);
    private final ProjectLlmRouter router = mock(ProjectLlmRouter.class);
    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmTimeoutResolver timeoutResolver = mock(LlmTimeoutResolver.class);

    private static LlmEndpointConfig endpoint(String provider) {
        return new LlmEndpointConfig(provider, "model-" + provider, "dummy-key",
                "http://localhost:4000/v1", LlmEndpointConfig.SOURCE_CONFIG);
    }

    private LlmFallbackChain chain(LlmRetryPolicy policy) {
        return new LlmFallbackChain(factory, router, coreGateway, policy,
                timeoutResolver, null, null, null);
    }

    @BeforeEach
    void routeSingleRealModeChain() {
        when(factory.isMock()).thenReturn(false);
        when(timeoutResolver.resolve()).thenReturn(Duration.ofSeconds(300));
    }

    /** : streaming-side stalling provider — records granted timeouts and
 * burns the fake clock, then fails retryably; pins that the shared fallback
 * loop applies the same per-retry clamp and hard stop to the streaming
 * entry as to blocking chat (one loop, one source). */
    private List<Duration> stallingStreamProvider(AtomicLong now) {
        List<Duration> granted = new ArrayList<>();
        when(factory.chatStreamFor(any(), any(), any(Duration.class),
                any(Duration.class), any())).thenAnswer(invocation -> {
            Duration timeout = invocation.getArgument(2);
            granted.add(timeout);
            now.addAndGet(Math.min(120_000L, timeout.toMillis()));
            throw new ServiceException("供应商连接失败", BizErrorCode.AI_PROVIDER_ERROR);
        });
        return granted;
    }

    @Test
    void 流式入口逐次重试同样钳到剩余窗口耗尽后硬停() {
        when(router.routeChain(PROJECT_ID)).thenReturn(List.of(endpoint("a")));
        when(timeoutResolver.resolveStreamIdle()).thenReturn(Duration.ofSeconds(90));
        AtomicLong now = new AtomicLong();
        ExtractDeadline deadline = new ExtractDeadline(400_000L, now::get);
        List<Duration> granted = stallingStreamProvider(now);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> chain(new LlmRetryPolicy(5, 1, 2)).chatStreamHandle(
                        ChatCompletionRequest.of("m", List.of()), PROJECT_ID, null,
                        event -> { }, deadline::clamp));

        assertEquals(List.of(Duration.ofSeconds(300), Duration.ofSeconds(280),
                        Duration.ofSeconds(160), Duration.ofSeconds(40)), granted,
                "流式重试超时必须=min(300s 键, 当时剩余窗口)，窗口耗尽即停手");
        assertEquals(BizErrorCode.AI_TIMEOUT, ex.getCode(),
                "流式硬停错误码=AI_TIMEOUT，禁伪装成供应商异常");
        assertTrue(now.get() <= deadline.deadlineEpochMillis(),
                "流式总墙钟必≤任务时限（假时钟实测 " + now.get() + "ms）");
    }

    @Test
    void 流式入口窗口起点即耗尽零外呼直接抛AI_TIMEOUT() {
        when(router.routeChain(PROJECT_ID)).thenReturn(List.of(endpoint("a")));
        ExtractDeadline spent = new ExtractDeadline(0L, () -> 1_000L);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> chain(new LlmRetryPolicy(3, 1, 2)).chatStreamHandle(
                        ChatCompletionRequest.of("m", List.of()), PROJECT_ID, null,
                        event -> { }, spent::clamp));

        verify(factory, never()).chatStreamFor(any(), any(), any(Duration.class),
                any(Duration.class), any());
        assertEquals(BizErrorCode.AI_TIMEOUT, ex.getCode());
    }

    /** Stalling provider: consumes min(120s, granted timeout) of the fake clock, then fails retryably. */
    private List<Duration> stallingProvider(AtomicLong now) {
        List<Duration> granted = new ArrayList<>();
        when(factory.chatFor(any(), any(), any(Duration.class))).thenAnswer(invocation -> {
            Duration timeout = invocation.getArgument(2);
            granted.add(timeout);
            now.addAndGet(Math.min(120_000L, timeout.toMillis()));
            throw new ServiceException("供应商连接失败", BizErrorCode.AI_PROVIDER_ERROR);
        });
        return granted;
    }

    @Test
    void 逐次重试钳到剩余窗口_耗尽后硬停不再发起调用() {
        when(router.routeChain(PROJECT_ID)).thenReturn(List.of(endpoint("a")));
        AtomicLong now = new AtomicLong();
        ExtractDeadline deadline = new ExtractDeadline(400_000L, now::get);
        List<Duration> granted = stallingProvider(now);
        LlmRetryPolicy policy = new LlmRetryPolicy(5, 1, 2);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> chain(policy).chat(ChatCompletionRequest.of("m", List.of()),
                        PROJECT_ID, null, false, deadline::clamp));

        assertEquals(List.of(Duration.ofSeconds(300), Duration.ofSeconds(280),
                        Duration.ofSeconds(160), Duration.ofSeconds(40)), granted,
                "每次重试超时必须=min(300s 键, 当时剩余窗口)，窗口耗尽即停手");
        verify(factory, times(4)).chatFor(any(), any(), any(Duration.class));
        assertEquals(BizErrorCode.AI_TIMEOUT, ex.getCode(),
                "硬停错误码=AI_TIMEOUT，禁伪装成供应商异常");
        assertTrue(ex.getMessage().contains("时长上限"),
                "回写人话须点名时长预算, 实际=" + ex.getMessage());
        assertTrue(now.get() <= deadline.deadlineEpochMillis(),
                "总墙钟必≤任务时限（假时钟实测 " + now.get() + "ms vs "
                        + deadline.deadlineEpochMillis() + "ms）");
    }

    @Test
    void 窗口起点即耗尽_零外呼直接抛AI_TIMEOUT() {
        when(router.routeChain(PROJECT_ID)).thenReturn(List.of(endpoint("a")));
        ExtractDeadline spent = new ExtractDeadline(0L, () -> 1_000L);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> chain(new LlmRetryPolicy(5, 1, 2)).chat(
                        ChatCompletionRequest.of("m", List.of()),
                        PROJECT_ID, null, false, spent::clamp));

        verify(factory, never()).chatFor(any(), any(), any(Duration.class));
        assertEquals(BizErrorCode.AI_TIMEOUT, ex.getCode());
    }

    @Test
    void 耗尽后降级链其余端点同样零外呼() {
        when(router.routeChain(PROJECT_ID)).thenReturn(List.of(endpoint("a"), endpoint("b")));
        ExtractDeadline spent = new ExtractDeadline(0L, () -> 1_000L);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> chain(new LlmRetryPolicy(3, 1, 2)).chat(
                        ChatCompletionRequest.of("m", List.of()),
                        PROJECT_ID, null, false, spent::clamp));

        verify(factory, never()).chatFor(any(), any(), any(Duration.class));
        assertEquals(BizErrorCode.AI_TIMEOUT, ex.getCode(),
                "全链耗尽聚合仍保留 AI_TIMEOUT 码，禁漂成供应商异常");
    }

    @Test
    void 无clamp调用方保持旧重试语义零漂移() {
        when(router.routeChain(PROJECT_ID)).thenReturn(List.of(endpoint("a")));
        AtomicLong now = new AtomicLong();
        List<Duration> granted = stallingProvider(now);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> chain(new LlmRetryPolicy(3, 1, 2)).chat(
                        ChatCompletionRequest.of("m", List.of()), PROJECT_ID, null, false));

        assertEquals(3, granted.size(), "缺省链仍烧满 3 次尝试（旧语义）");
        assertTrue(granted.stream().allMatch(t -> t.equals(Duration.ofSeconds(300))),
                "无任务窗口时每次超时=解析键值原样");
        assertEquals(BizErrorCode.AI_PROVIDER_ERROR, ex.getCode());
    }
}
