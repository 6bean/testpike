package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.aiservice.doc.domain.component.ExtractDeadline;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Door-level isolation of the task-budget hard stop (live incident doc 9037:
 * provider calls kept landing 155-378s after the run window expired at
 * +5400s). Sibling of the deadline hard-stop suite; this one pins the two
 * seams the shared door itself gets wrong:
 * <p>① the ledger budget seam's CLAMPED value must reach the provider call —
 * probing it only for null and handing the provider the raw K8 timeout lets
 * a nearly-spent window burn a full-length call;
 * <p>② a spent-window stop must never be laundered as a provider failure by
 * the degrade catch — an endpoint that was never attempted must not appear
 * in the aggregated provider-failure detail (false audit attribution).
 * <p>Infra mocked per L2 rules (factory/router/gateway/writer); the door
 * loop, retry policy and deadline component run real code with fake clocks.
 */
class LlmFallbackChainBudgetHardStopIsolationTest {

    private static final long PROJECT_ID = 7L;

    private final LlmProviderFactory factory = mock(LlmProviderFactory.class);
    private final ProjectLlmRouter router = mock(ProjectLlmRouter.class);
    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmTimeoutResolver timeoutResolver = mock(LlmTimeoutResolver.class);
    private final AiCallLogWriter writer = mock(AiCallLogWriter.class);

    private static LlmEndpointConfig endpoint(String provider) {
        return new LlmEndpointConfig(provider, "model-" + provider, "dummy-key",
                "http://localhost:4000/v1", LlmEndpointConfig.SOURCE_CONFIG);
    }

    private LlmFallbackChain door(LlmRetryPolicy policy) {
        return new LlmFallbackChain(factory, router, coreGateway, policy,
                timeoutResolver, null, null, null);
    }

    @BeforeEach
    void routeRealModeDoor() {
        when(factory.isMock()).thenReturn(false);
        when(timeoutResolver.resolve()).thenReturn(Duration.ofSeconds(300));
    }

    /**
     * Incident face ①: the ledger seam reports the remaining window (7s) but
     * the door currently discards that value and hands the provider the full
     * resolved 300s timeout. Pinned: what the seam returns is what the
     * provider call is granted.
     */
    @Test
    void 台账预算面钳制值必须实际下发给供应商调用() {
        when(router.routeChain(PROJECT_ID)).thenReturn(List.of(endpoint("a")));
        List<Duration> granted = new ArrayList<>();
        when(factory.chatFor(any(), any(), any(Duration.class))).thenAnswer(invocation -> {
            granted.add(invocation.getArgument(2));
            return new LlmChatResult("ok", "a", "model-a", 1, 1, 5L);
        });
        LlmFallbackChain door = door(new LlmRetryPolicy(3, 1, 2));
        door.enableCallLedger(writer, () -> "gen-token",
                "F2 需求点提取外呼", timeout -> Duration.ofSeconds(7));

        door.chat(ChatCompletionRequest.of("m", List.of()), PROJECT_ID, null);

        assertEquals(List.of(Duration.ofSeconds(7)), granted,
                "门台账面钳到 7s 就必须按 7s 下发；丢弃钳制值=窗口将尽仍按 300s 满烧");
    }

    /**
     * Incident face ②: endpoint a fails, then the per-call clamp reports the
     * window spent right before endpoint b is attempted. Pinned: the stop
     * surfaces with the seam's own budget message, endpoint b never
     * reaches the provider, and the aggregated provider-failure detail does
     * NOT claim b as a failed supplier (b was never attempted — false audit).
     */
    @Test
    void 窗口中途耗尽的硬停不得把未发起的端点洗白成失败供应商明细() {
        when(router.routeChain(PROJECT_ID)).thenReturn(List.of(endpoint("a"), endpoint("b")));
        AtomicLong now = new AtomicLong();
        ExtractDeadline deadline = new ExtractDeadline(150_000L, now::get);
        when(factory.chatFor(any(), any(), any(Duration.class))).thenAnswer(invocation -> {
            now.addAndGet(150_000L);
            throw new ServiceException("供应商连接失败", BizErrorCode.AI_PROVIDER_ERROR);
        });

        ServiceException ex = assertThrows(ServiceException.class,
                () -> door(new LlmRetryPolicy(1, 1, 2)).chat(
                        ChatCompletionRequest.of("m", List.of()), PROJECT_ID,
                        null, false, deadline::clamp));

        assertEquals(BizErrorCode.AI_TIMEOUT, ex.getCode());
        assertTrue(ex.getMessage().contains("时长上限"),
                "硬停须点名任务时长预算, 实际=" + ex.getMessage());
        assertFalse(ex.getMessage().contains("全链失败明细"),
                "b 端点从未发起，禁混进失败供应商明细（审计假归因）, 实际=" + ex.getMessage());
        verify(factory, times(1)).chatFor(any(), any(), any(Duration.class));
    }

    /** Guard pin: the ledger seam's spent form (throw) hard-stops the door
     *  before any provider attempt with zero ledger rows booked. */
    @Test
    void 台账预算面抛耗尽异常时门原样硬停零外呼() {
        when(router.routeChain(PROJECT_ID)).thenReturn(List.of(endpoint("a")));
        ExtractDeadline spent = new ExtractDeadline(0L, () -> 1_000L);
        LlmFallbackChain door = door(new LlmRetryPolicy(3, 1, 2));
        door.enableCallLedger(writer, () -> "gen-token", "F2 需求点提取外呼", spent::clamp);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> door.chat(ChatCompletionRequest.of("m", List.of()), PROJECT_ID, null));

        assertEquals(BizErrorCode.AI_TIMEOUT, ex.getCode());
        verify(factory, never()).chatFor(any(), any(), any(Duration.class));
        verify(writer, never()).insert(anyLong(), anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                anyLong(), any(), any());
    }

    /** Guard pin: the door's null-return seam form still aborts with the
     *  door-level budget-spent message and zero provider calls. */
    @Test
    void 台账预算面返回null形态仍按门级硬停文案中止() {
        when(router.routeChain(PROJECT_ID)).thenReturn(List.of(endpoint("a")));
        UnaryOperator<Duration> nullForm = timeout -> null;
        LlmFallbackChain door = door(new LlmRetryPolicy(3, 1, 2));
        door.enableCallLedger(writer, () -> "gen-token", "F2 需求点提取外呼", nullForm);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> door.chat(ChatCompletionRequest.of("m", List.of()), PROJECT_ID, null));

        assertEquals(BizErrorCode.AI_TIMEOUT, ex.getCode());
        assertTrue(ex.getMessage().contains("任务时间窗口已用尽"),
                "null 形态保留门级硬停文案, 实际=" + ex.getMessage());
        verify(factory, never()).chatFor(any(), any(), any(Duration.class));
    }
}
