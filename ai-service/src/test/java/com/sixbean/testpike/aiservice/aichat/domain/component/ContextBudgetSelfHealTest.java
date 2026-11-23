package com.sixbean.testpike.aiservice.aichat.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolDef;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolFunction;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Over-budget self-heal ( item 7, adjudicated posture: local pre-flight
 * first, provider-400 summary retry as the safety net).
 *
 * <p>Two faces asserted here:
 * <ul>
 * <li>pre-flight — a packet above the hot-updatable ratio of the endpoint
 * prompt limit folds BEFORE the round is sent; when the switch is off the
 * legacy hard-limit posture is byte-identical (the pre-flight never
 * fires without the heal switch);</li>
 * <li>the 400 safety net — a genuine provider context-length rejection
 * (different installs, different vendor limits than the local config)
 * folds the transcript once and hands the caller a retryable packet; a
 * disabled switch refuses with the honest guidance wording (where to
 * enable + the parameter name), and a 400 that is NOT a context-length
 * signal is passed through untouched (folding cannot fix a malformed
 * payload and must not mask it).</li>
 * </ul>
 *
 * usage channel as a live round, so its tokens land on tp_ai_chat_message —
 * the live face of that channel was verified end-to-end with a 654k-token
 * round (message ledger row prompt=654424); this L2 fixture pins the policy
 * trigger itself without depending on a vendor 400.
 */
class ContextBudgetSelfHealTest {

    private static final long PROJECT = 7L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Deterministic mid-size CJK block: tens of tokens, never near 240. */
    private static String text(String mark) {
        return mark + "：订单超时场景的退款链路需要覆盖幂等与并发补偿，评审工作台会退回草案。";
    }

    private static LlmEndpointConfig endpointWithLimit(int promptLimitTokens) {
        return new LlmEndpointConfig("mock", "m-gate", "k", "http://localhost", "DB",
                promptLimitTokens, null, null);
    }

    private static ChatCompletionRequest request(List<ChatMessage> messages) {
        return new ChatCompletionRequest("m-gate", messages, null, null);
    }

    private static AiCtxConfigResolver resolver(boolean healOn, int preflightRatio) {
        AiCtxConfigResolver resolver = mock(AiCtxConfigResolver.class);
        when(resolver.sessionKeepRecentTurns()).thenReturn(1);
        when(resolver.healEnabled()).thenReturn(healOn);
        when(resolver.healPreflightRatio()).thenReturn(preflightRatio);
        return resolver;
    }

    private static LoopContextBudgetGate gate(int promptLimitTokens, boolean healOn,
                                              int preflightRatio, DialogRollingSummary summary) {
        ProjectLlmRouter router = mock(ProjectLlmRouter.class);
        when(router.route(anyLong())).thenReturn(endpointWithLimit(promptLimitTokens));
        return new LoopContextBudgetGate(router, resolver(healOn, preflightRatio), summary);
    }

    private static ServiceException contextOverflow400() {
        return new ServiceException("AI 供应商调用失败: This model's maximum context length is "
                + "8192 tokens, however you requested 400000 tokens（HTTP 400）",
                BizErrorCode.AI_PROVIDER_ERROR);
    }

    private void stubSummary(DialogRollingSummary summary) {
        ConversationSummarySource turn = mock(ConversationSummarySource.class);
        when(turn.summarize(any())).thenReturn("早前对话已归并为要点。");
        when(summary.forTurn(any(), anyLong())).thenReturn(turn);
    }

    @Test
    void packetAbovePreflightRatioButUnderHardLimitFoldsBeforeSending() {
        // limit 240, ratio 5 → pre-flight threshold 12 tokens: the packet
        // (≈tens of tokens) is under the wall yet over the early line, so it
        // must fold instead of riding into the vendor's face.
        DialogRollingSummary summary = mock(DialogRollingSummary.class);
        stubSummary(summary);
        LoopContextBudgetGate gate = gate(240, true, 5, summary);

        ChatCompletionRequest original = request(List.of(
                new ChatMessage("system", "你是测试助手"),
                new ChatMessage("user", text("提问")),
                new ChatMessage("assistant", "好的")));

        ChatCompletionRequest fitted = gate.fit(original, PROJECT);

        assertNotSame(original, fitted);
        assertTrue(fitted.messages().get(0).textContent()
                .contains(ConversationContextAssembler.SUMMARY_HEADER_PREFIX),
                fitted.messages().toString());
    }

    @Test
    void preflightDisabledKeepsLegacyHardLimitPassThrough() {
        DialogRollingSummary summary = mock(DialogRollingSummary.class);
        LoopContextBudgetGate gate = gate(240, false, 5, summary);
        ChatCompletionRequest original = request(List.of(
                new ChatMessage("system", "你是测试助手"),
                new ChatMessage("user", text("提问")),
                new ChatMessage("assistant", "好的")));

        assertSame(original, gate.fit(original, PROJECT));
        verify(summary, never()).forTurn(any(), anyLong());
    }

    @Test
    void toolDefinitionsContributeToTheBudgetEstimate() {
        // The messages alone sit under the 240 hard limit; adding the tool
        // schema (name + description + parameter JSON) crosses it — the
        // estimate must count tools, not just chat text.
        DialogRollingSummary summary = mock(DialogRollingSummary.class);
        stubSummary(summary);
        ProjectLlmRouter router = mock(ProjectLlmRouter.class);
        when(router.route(anyLong())).thenReturn(endpointWithLimit(240));
        LoopContextBudgetGate gate = new LoopContextBudgetGate(router, resolver(true, 100), summary);
        ChatToolFunction function = new ChatToolFunction("warehouse.inventory.lookup",
                text("工具说明") + text("工具说明") + text("工具说明"),
                MAPPER.createObjectNode()
                        .set("properties", MAPPER.valueToTree(java.util.Map.of(
                                "sku", java.util.Map.of("type", "string",
                                        "description", text("参数说明") + text("参数说明")),
                                "warehouse", java.util.Map.of("type", "string",
                                        "description", text("仓库维度说明"))))));
        ChatCompletionRequest withTools = new ChatCompletionRequest("m-gate", List.of(
                new ChatMessage("system", "你是测试助手"),
                new ChatMessage("user", "帮我查询库存"),
                new ChatMessage("assistant", "正在调用工具")),
                null, null, List.of(new ChatToolDef(function)), null);

        ChatCompletionRequest fitted = gate.fit(withTools, PROJECT);

        assertNotSame(withTools, fitted);
        verify(summary).forTurn(any(), anyLong());
    }

    @Test
    void providerOverflow400FoldsTranscriptOnceForTheRetryRing() {
        // Vendor rejected a packet the LOCAL config considered fine (limit
        // 1M mis-set vs the vendor's real window): the ring must fold anyway.
        DialogRollingSummary summary = mock(DialogRollingSummary.class);
        stubSummary(summary);
        LoopContextBudgetGate gate = gate(1_000_000, true, 95, summary);
        ChatCompletionRequest sent = request(List.of(
                new ChatMessage("system", "你是测试助手"),
                new ChatMessage("user", text("第一轮")),
                new ChatMessage("assistant", "答复一"),
                new ChatMessage("user", text("第二轮")),
                new ChatMessage("assistant", "答复二")));

        ChatCompletionRequest healed =
                gate.healAfterOverflow(sent, PROJECT, contextOverflow400());

        assertNotSame(sent, healed);
        assertTrue(healed.messages().get(0).textContent()
                .contains(ConversationContextAssembler.SUMMARY_HEADER_PREFIX));
        assertEquals("答复二", healed.messages().get(healed.messages().size() - 1).textContent());
        verify(summary).forTurn(any(), anyLong());
    }

    @Test
    void disabledHealRefusesWithTheSettingPathAndTheKeyNamed() {
        DialogRollingSummary summary = mock(DialogRollingSummary.class);
        LoopContextBudgetGate gate = gate(1_000_000, false, 95, summary);
        ChatCompletionRequest sent = request(List.of(
                new ChatMessage("system", "你是测试助手"),
                new ChatMessage("user", text("第一轮")),
                new ChatMessage("assistant", "答复一")));

        ServiceException refusal = assertThrows(ServiceException.class,
                () -> gate.healAfterOverflow(sent, PROJECT, contextOverflow400()));

        assertTrue(refusal.getMessage().contains("系统管理 → 系统配置"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("ai.ctx.over-budget-heal.enabled"),
                refusal.getMessage());
        assertTrue(refusal.getMessage().contains("maximum context length"),
                refusal.getMessage());
        verify(summary, never()).forTurn(any(), anyLong());
    }

    @Test
    void nonContext400IsPassedThroughUntouched() {
        DialogRollingSummary summary = mock(DialogRollingSummary.class);
        stubSummary(summary);
        LoopContextBudgetGate gate = gate(1_000_000, true, 95, summary);
        ServiceException unrelated = new ServiceException(
                "AI 供应商调用失败: unsupported response_format 字段（HTTP 400）",
                BizErrorCode.AI_PROVIDER_ERROR);

        ServiceException thrown = assertThrows(ServiceException.class, () -> gate
                .healAfterOverflow(request(List.of(
                        new ChatMessage("system", "s"),
                        new ChatMessage("user", text("u")),
                        new ChatMessage("assistant", "a"))), PROJECT, unrelated));

        assertSame(unrelated, thrown);
        verify(summary, never()).forTurn(any(), anyLong());
    }
}
