package com.sixbean.testpike.aiservice.aichat.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ChatMessageRemote;
import com.sixbean.testpike.aiservice.llm.api.dto.AssistantToolCall;
import com.sixbean.testpike.aiservice.llm.api.dto.AssistantToolFunction;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * (AP-2): the per-round prompt-budget gate for dialog loops.
 * An over-budget loop packet must fold its OLDER exchange units into one
 * rolling-summary section (AP-1 ② compressor, same section headers as the
 * turn assembler) instead of letting the whole order fail at the provider.
 * The fold never splits an assistant(tool_calls) message from its tool
 * results — units are the atomic cut granularity.
 */
class LoopContextBudgetGateTest {

    private static final long PROJECT = 7L;

    /** Long CJK text so the real tokenizer clears any small token limit. */
    private static String longText(String mark) {
        return mark + "：订单超时场景的退款链路需要覆盖幂等与并发补偿，"
                + "测试数据工厂要预热三百条待支付订单并注入两次回调延迟，"
                + "断言密度不足时评审工作台会退回草案并要求补充出处锚点。";
    }

    private static LlmEndpointConfig smallLimitEndpoint() {
        return new LlmEndpointConfig("mock", "m-gate", "k", "http://localhost", "DB",
                120, null, null);
    }

    private static LlmEndpointConfig roomyEndpoint() {
        return new LlmEndpointConfig("mock", "m-gate", "k", "http://localhost", "DB",
                1_000_000, null, null);
    }

    private static ChatMessage assistantWithCall(String content, String callId, String tool,
                                                 String args) {
        return new ChatMessage("assistant", content, null, List.of(new AssistantToolCall(
                callId, new AssistantToolFunction(tool, args))));
    }

    private static ChatCompletionRequest request(List<ChatMessage> messages) {
        return new ChatCompletionRequest("m-gate", messages, null, null);
    }

    private static AiCtxConfigResolver resolverKeeping(int turns) {
        AiCtxConfigResolver resolver = mock(AiCtxConfigResolver.class);
        when(resolver.sessionKeepRecentTurns()).thenReturn(turns);
        return resolver;
    }

    @Test
    void requestUnderEndpointBudgetPassesThroughWithoutFolding() {
        ProjectLlmRouter router = mock(ProjectLlmRouter.class);
        when(router.route(anyLong())).thenReturn(roomyEndpoint());
        DialogRollingSummary summary = mock(DialogRollingSummary.class);
        LoopContextBudgetGate gate = new LoopContextBudgetGate(router,
                resolverKeeping(2), summary);
        ChatCompletionRequest fitted = gate.fit(request(List.of(
                new ChatMessage("system", "你是测试助手"),
                new ChatMessage("user", "帮我看看用例"),
                new ChatMessage("assistant", "好的"))), PROJECT);

        assertEquals(3, fitted.messages().size());
        verify(summary, never()).forTurn(any(), anyLong());
    }

    @Test
    void packetBelowLoopShapePassesWithoutBudgetCheck() {
        // A <3-message packet never enters the loop gate: the first single
        // shot is guarded on the one-shot call chain — the refusal rule keeps
        // this early exit byte-identical (no limit is read here at all).
        ProjectLlmRouter router = mock(ProjectLlmRouter.class);
        DialogRollingSummary summary = mock(DialogRollingSummary.class);
        LoopContextBudgetGate gate = new LoopContextBudgetGate(router,
                resolverKeeping(2), summary);
        ChatCompletionRequest original = request(List.of(
                new ChatMessage("system", longText("系统头")),
                new ChatMessage("user", longText("唯一提问"))));

        assertSame(original, gate.fit(original, PROJECT));
        verify(router, never()).route(anyLong());
    }

    @Test
    void overBudgetSingleExchangeWithNothingToFoldIsRefusedNotSent() {
        ProjectLlmRouter router = mock(ProjectLlmRouter.class);
        when(router.route(anyLong())).thenReturn(smallLimitEndpoint());
        DialogRollingSummary summary = mock(DialogRollingSummary.class);
        LoopContextBudgetGate gate = new LoopContextBudgetGate(router,
                resolverKeeping(2), summary);

        ServiceException refusal = assertThrows(ServiceException.class, () -> gate.fit(request(List.of(
                new ChatMessage("system", longText("系统头")),
                assistantWithCall(longText("助手调用"), "c1", "stock.query", "{\"sku\":\"A\"}"),
                ChatMessage.tool("c1", longText("执行观测")))), PROJECT));

        assertEquals(BizErrorCode.AI_PROMPT_TOO_LARGE, refusal.getCode());
        assertTrue(refusal.getMessage().contains("超出模型上限"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("请新开对话或缩短本轮输入"), refusal.getMessage());
        verify(summary, never()).forTurn(any(), anyLong());
    }

    @Test
    void overBudgetFoldsOlderUnitsIntoSummarySectionKeepsRecentRaw() {
        ProjectLlmRouter router = mock(ProjectLlmRouter.class);
        when(router.route(anyLong())).thenReturn(smallLimitEndpoint());
        DialogRollingSummary summary = mock(DialogRollingSummary.class);
        when(summary.forTurn(eq("m-gate"), eq(PROJECT))).thenReturn(rows -> {
            assertEquals(4, rows.size(), "one transcript row per folded unit");
            assertEquals("user", rows.get(0).role());
            assertTrue(rows.get(3).content().contains("stock.query"),
                    "assistant row carries its tool call name: " + rows.get(3).content());
            assertTrue(rows.get(3).content().contains("库存 12 件"),
                    "assistant row carries the tool observation: " + rows.get(3).content());
            return "早前两轮谈了订单超时与退款规则";
        });
        LoopContextBudgetGate gate = new LoopContextBudgetGate(router,
                resolverKeeping(2), summary);

        ChatCompletionRequest fitted = gate.fit(request(List.of(
                new ChatMessage("system", "你是测试助手"),
                new ChatMessage("user", longText("问题一")),
                new ChatMessage("assistant", "答一"),
                new ChatMessage("user", longText("问题二")),
                assistantWithCall("查询库存", "c1", "stock.query", "{\"sku\":\"A\"}"),
                ChatMessage.tool("c1", "库存 12 件"),
                new ChatMessage("user", "问题三"),
                new ChatMessage("assistant", "答三"))), PROJECT);

        ChatMessage head = fitted.messages().get(0);
        assertEquals("system", head.role());
        assertTrue(head.content().startsWith("你是测试助手"),
                "frozen system head survives the fold");
        assertTrue(head.content().contains("更早对话摘要"),
                "summary section rides the leading system message: " + head.content());
        assertTrue(head.content().contains("早前两轮谈了订单超时与退款规则"));
        List<ChatMessage> tail = fitted.messages().subList(1, fitted.messages().size());
        assertEquals(List.of("user", "assistant"), tail.stream().map(ChatMessage::role).toList());
        assertEquals("问题三", tail.get(0).textContent());
        assertEquals(3, fitted.messages().size(), "head + two raw kept messages");
    }

    @Test
    void foldBoundaryNeverSplitsAssistantToolCallPair() {
        ProjectLlmRouter router = mock(ProjectLlmRouter.class);
        when(router.route(anyLong())).thenReturn(new LlmEndpointConfig("mock", "m-gate",
                "k", "http://localhost", "DB", 150, null, null));
        DialogRollingSummary summary = mock(DialogRollingSummary.class);
        when(summary.forTurn(any(), anyLong())).thenReturn(rows -> "早前讨论已压缩");
        LoopContextBudgetGate gate = new LoopContextBudgetGate(router,
                resolverKeeping(1), summary);

        ChatCompletionRequest fitted = gate.fit(request(List.of(
                new ChatMessage("system", "你是测试助手"),
                new ChatMessage("user", longText("问题一")),
                new ChatMessage("assistant", "答一"),
                assistantWithCall("查执行结果", "c9", "exec.result", "{\"taskId\":1}"),
                ChatMessage.tool("c9", longText("执行观测")))), PROJECT);

        List<ChatMessage> kept = fitted.messages().subList(1, fitted.messages().size());
        assertEquals("assistant", kept.get(0).role(),
                "the kept window opens on the assistant that owns the tool result");
        assertEquals("tool", kept.get(1).role());
        assertEquals("c9", kept.get(1).toolCallId());
    }

    @Test
    void blankSummaryOverBudgetIsRefusedNotSent() {
        ProjectLlmRouter router = mock(ProjectLlmRouter.class);
        when(router.route(anyLong())).thenReturn(smallLimitEndpoint());
        DialogRollingSummary summary = mock(DialogRollingSummary.class);
        when(summary.forTurn(any(), anyLong())).thenReturn(rows -> "   ");
        LoopContextBudgetGate gate = new LoopContextBudgetGate(router,
                resolverKeeping(2), summary);

        assertThrows(ServiceException.class, () -> gate.fit(request(List.of(
                new ChatMessage("system", longText("系统头")),
                new ChatMessage("user", longText("问题一")),
                new ChatMessage("assistant", "答一"),
                new ChatMessage("user", longText("问题二")),
                new ChatMessage("assistant", "答二"))), PROJECT));
    }

    @Test
    void summariserCrashOverBudgetIsRefusedNotSent() {
        ProjectLlmRouter router = mock(ProjectLlmRouter.class);
        when(router.route(anyLong())).thenReturn(smallLimitEndpoint());
        DialogRollingSummary summary = mock(DialogRollingSummary.class);
        when(summary.forTurn(any(), anyLong())).thenReturn(rows -> {
            throw new IllegalStateException("summary channel down");
        });
        LoopContextBudgetGate gate = new LoopContextBudgetGate(router,
                resolverKeeping(2), summary);

        ServiceException refusal = assertThrows(ServiceException.class, () -> gate.fit(request(List.of(
                new ChatMessage("system", longText("系统头")),
                new ChatMessage("user", longText("问题一")),
                new ChatMessage("assistant", "答一"),
                new ChatMessage("user", longText("问题二")),
                new ChatMessage("assistant", "答二"))), PROJECT));
        assertEquals(BizErrorCode.AI_PROMPT_TOO_LARGE, refusal.getCode());
    }

    @Test
    void foldedPacketStillOverBudgetIsRefusedNotSent() {
        ProjectLlmRouter router = mock(ProjectLlmRouter.class);
        when(router.route(anyLong())).thenReturn(smallLimitEndpoint());
        DialogRollingSummary summary = mock(DialogRollingSummary.class);
        when(summary.forTurn(any(), anyLong())).thenReturn(rows -> "压缩段落");
        LoopContextBudgetGate gate = new LoopContextBudgetGate(router,
                resolverKeeping(1), summary);

        ServiceException refusal = assertThrows(ServiceException.class, () -> gate.fit(request(List.of(
                new ChatMessage("system", longText("系统头")),
                new ChatMessage("user", longText("问题一")),
                new ChatMessage("assistant", longText("答一")))), PROJECT));
        assertEquals(BizErrorCode.AI_PROMPT_TOO_LARGE, refusal.getCode());
        verify(summary).forTurn(eq("m-gate"), eq(PROJECT));
    }

    @Test
    void foldKeepsModelSamplingAndToolBindingOfTheRequest() {
        ProjectLlmRouter router = mock(ProjectLlmRouter.class);
        when(router.route(anyLong())).thenReturn(new LlmEndpointConfig("mock", "m-gate",
                "k", "http://localhost", "DB", 150, null, null));
        DialogRollingSummary summary = mock(DialogRollingSummary.class);
        when(summary.forTurn(any(), anyLong())).thenReturn(rows -> "压缩段落");
        LoopContextBudgetGate gate = new LoopContextBudgetGate(router,
                resolverKeeping(1), summary);
        ChatCompletionRequest oversized = new ChatCompletionRequest("m-gate", List.of(
                new ChatMessage("system", "你是测试助手"),
                new ChatMessage("user", longText("问一")),
                new ChatMessage("assistant", "答一"),
                new ChatMessage("user", longText("问二"))), 0.3, 512, null, "auto");

        ChatCompletionRequest fitted = gate.fit(oversized, PROJECT);

        assertEquals("m-gate", fitted.model());
        assertEquals(0.3, fitted.temperature());
        assertEquals(512, fitted.maxTokens());
        assertEquals("auto", fitted.toolChoice());
        assertEquals(2, fitted.messages().size());
    }

    @Test
    void foldedTranscriptRowsRideTheDialogCompressorContract() {
        // The fold hands ChatMessageRemote mirror rows to the SINGLE compressor
        // (DialogRollingSummary) — no second summariser, same <input> contract.
        ProjectLlmRouter router = mock(ProjectLlmRouter.class);
        when(router.route(anyLong())).thenReturn(new LlmEndpointConfig("mock", "m-gate",
                "k", "http://localhost", "DB", 60, null, null));
        AtomicReference<List<ChatMessageRemote>> captured = new AtomicReference<>();
        DialogRollingSummary summary = mock(DialogRollingSummary.class);
        when(summary.forTurn(any(), anyLong())).thenReturn(rows -> {
            captured.set(rows);
            return "压缩段落";
        });
        LoopContextBudgetGate gate = new LoopContextBudgetGate(router,
                resolverKeeping(1), summary);

        ChatCompletionRequest fitted = gate.fit(request(List.of(
                new ChatMessage("system", "你是测试助手"),
                new ChatMessage("user", longText("问题一")),
                assistantWithCall("调用说明", "c5", "case.list", "{\"page\":1}"),
                ChatMessage.tool("c5", "共 42 条用例"),
                new ChatMessage("user", "最新问题"))), PROJECT);

        List<ChatMessageRemote> rows = captured.get();
        assertNotNull(rows);
        assertEquals(2, rows.size());
        assertEquals("user", rows.get(0).role());
        assertEquals("assistant", rows.get(1).role());
        assertTrue(rows.get(1).content().contains("case.list"));
        assertTrue(rows.get(1).content().contains("共 42 条用例"));
        assertEquals(2, fitted.messages().size(), "head + the kept final user turn");
    }
}
