package com.sixbean.testpike.aiservice.doc.domain.service.impl;

import com.sixbean.testpike.common.util.LlmExtractMarkers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.doc.domain.component.BudgetPolicy;
import com.sixbean.testpike.aiservice.doc.domain.component.ContextBudgetPlanner;
import com.sixbean.testpike.aiservice.doc.domain.component.ModelAdaptiveBudget;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.api.dto.LlmConfigBrief;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.AiDocExtractMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * (pinned exactly: 9 groups -> 11 calls); ④a reduce-capacity refusal degrades the overview
 * round with zero summarisation and never aborts extraction ;
 *
 */
class DocExtractServiceImplE8Test {

    private static final long DOC_ID = 11L;
    private static final long PROJECT_ID = 3L;
    private static final String TOKEN = "e8-token-001";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
 
    private final DocExtractServiceImpl service = new DocExtractServiceImpl(
            coreGateway, llmChain, llmRouter, new ObjectMapper(),
            new com.sixbean.testpike.aiservice.llm.domain.component.TokenCounter());

    private static LlmEndpointConfig endpoint(Integer promptLimit) {
        return new LlmEndpointConfig("openai", "routed-model", "dummy-key",
                "http://localhost:4000/v1", LlmEndpointConfig.SOURCE_PROJECT, promptLimit);
    }

    private static AiDocExtractMessage message() {
        return new AiDocExtractMessage(DOC_ID, PROJECT_ID, 11L, null, TOKEN);
    }

    private static Map<String, Object> chunk(int seq, String chapter, String content) {
        return Map.of("seq", seq, "chapter", chapter, "content", content);
    }

    private void stubRoute(LlmEndpointConfig endpoint) {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint);
    }

    private void stubChunks(List<Map<String, Object>> chunks) {
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(chunks);
    }

    /** sys_config stub face : safety factor of the adaptive budget plus
 * the five BudgetPolicy keys — null values fall back to the default set. */
    private void stubConfigs(String factor, String ratio, String minT, String maxT,
                             String trigger, String levels) {
        when(coreGateway.getSysConfig(ModelAdaptiveBudget.KEY_SAFETY_FACTOR)).thenReturn(factor);
        when(coreGateway.getSysConfig(BudgetPolicy.KEY_SUMMARY_RATIO)).thenReturn(ratio);
        when(coreGateway.getSysConfig(BudgetPolicy.KEY_SUMMARY_MIN_TOKENS)).thenReturn(minT);
        when(coreGateway.getSysConfig(BudgetPolicy.KEY_SUMMARY_MAX_TOKENS)).thenReturn(maxT);
        when(coreGateway.getSysConfig(BudgetPolicy.KEY_REDUCE_TRIGGER_GROUPS)).thenReturn(trigger);
        when(coreGateway.getSysConfig(BudgetPolicy.KEY_REDUCE_MAX_LEVELS)).thenReturn(levels);
    }

    private void stubDefaults() {
        stubConfigs(null, null, null, null, null, null);
    }

    private static LlmChatResult summary(String text) {
        return new LlmChatResult(text, "openai", "routed-model", 10, 5, 1L);
    }

    private static LlmChatResult extractJson(int items) {
        StringBuilder sb = new StringBuilder("{\"requirements\":[");
        for (int i = 0; i < items; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"title\":\"需求").append(i).append("\",\"acceptanceCriteria\":\"标准")
              .append(i).append("\",\"chapter\":\"章节\",\"confidence\":0.8}");
        }
        return new LlmChatResult(sb.append("]}").toString(), "openai", "routed-model", 10, 5, 1L);
    }

    
    private List<ChatCompletionRequest> captureRequests() {
        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmChain, atLeast(0)).chat(captor.capture(), eq(PROJECT_ID),
                any(LlmFallbackNotice.class), anyBoolean(), any());
        return captor.getAllValues();
    }

    private List<ChatCompletionRequest> summaryRequests() {
        return captureRequests().stream()
                .filter(r -> r.messages().get(0).content().contains(LlmExtractMarkers.DOC_EXTRACT_SUMMARY))
                .toList();
    }

 
    @Test
    void 预剪枝后达标_零摘要直发() {
        // Discriminator (probe-measured 34/22/14 token fixture replicated x3 so
        // the band stays stable next to the coupled adaptive budget): unpruned
        // total 144 > 120 triggers the pre-pass; pruned 108 <= 120 sends raw.
        // Budget 120 = catalog limit 1200 x factor 0.1 (ModelAdaptiveBudget face).
        stubRoute(endpoint(1200));
        stubConfigs("0.1", null, null, null, null, null);
        stubChunks(List.of(
                chunk(1, "登录", "系统应支持账号密码登录，连续失败五次锁定十五分钟。\n---\n第 2 页 共 9 页"),
                chunk(2, "登出", "系统应支持主动登出并失效会话。"),
                chunk(3, "登录", "系统应支持账号密码登录，连续失败五次锁定十五分钟。\n---\n第 2 页 共 9 页"),
                chunk(4, "登出", "系统应支持主动登出并失效会话。"),
                chunk(5, "登录", "系统应支持账号密码登录，连续失败五次锁定十五分钟。\n---\n第 2 页 共 9 页"),
                chunk(6, "登出", "系统应支持主动登出并失效会话。")));
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean(), any()))
                .thenReturn(extractJson(2));

        service.extract(message());

        // Pruned-in-budget direct send = zero summarisation calls (the TC-CTX
        // package through the automatic headroom ceiling of
        // ai.doc.final-bucket-max-tokens (pruned 108 tokens > auto 72), so the
        // model-call count is no longer the zero-summarisation witness; the
        // summary-marker filter is, plus a hard non-empty floor on the final
        // plane (no conditional wrapping).
        assertTrue(summaryRequests().isEmpty(), "剪后预算内应零摘要");
        assertTrue(captureRequests().size() >= 1, "剪后预算内必须直发原文进入最终提取");
        verify(coreGateway).writeExtractionResult(eq(DOC_ID), any(), eq(TOKEN));
        verify(coreGateway, never()).markExtractionFailed(anyLong(), any(), any());
    }

 
    @Test
    void 摘要prompt含长度契约() {
        stubRoute(endpoint(1200));
        stubConfigs("0.1", null, null, null, null, null);
        stubChunks(List.of(
                chunk(1, "A", "字".repeat(300)),
                chunk(2, "B", "字".repeat(300))));
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    ChatCompletionRequest req = inv.getArgument(0);
                    return req.messages().get(0).content().contains(LlmExtractMarkers.DOC_EXTRACT_SUMMARY)
                            ? summary("摘要内容") : extractJson(1);
                });

        service.extract(message());

        ChatMessage system = summaryRequests().get(0).messages().get(0);
        String prompt = system.content();
        assertTrue(prompt.contains("281"), "长度契约须含 clamp 结果字符数: " + prompt);
        assertTrue(prompt.contains("长度契约"), prompt);
        assertFalse(prompt.contains("简短"), "禁不可测措辞「简短」");
    }

 
    @Test
    void 九组归并_摘要调用数精确十一() {
        stubRoute(endpoint(1200));
        stubConfigs("0.1", null, null, null, "6", "2");
        stubChunks(java.util.stream.IntStream.rangeClosed(1, 9)
                .mapToObj(i -> chunk(i, "章节" + i, "字".repeat(200))).toList());
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    ChatCompletionRequest req = inv.getArgument(0);
                    return req.messages().get(0).content().contains(LlmExtractMarkers.DOC_EXTRACT_SUMMARY)
                            ? summary("摘要") : extractJson(1);
                });

        service.extract(message());

        assertEquals(11, summaryRequests().size(), "摘要调用=9+2 逐级组数和");
        verify(coreGateway).writeExtractionResult(eq(DOC_ID), any(), eq(TOKEN));
    }

 /** 40 groups need 3 levels &gt; max-levels 2: the overview round degrades with
 * zero summarisation, extraction continues ( F2 ruled posture,
 * supersedes the former abort-and-fail-write-back contract pinned here). */
    @Test
    void 层级不可容纳_概览轮降级不中止提取() {
        stubRoute(endpoint(1200));
        stubConfigs("0.1", null, null, null, "6", "2");
        stubChunks(java.util.stream.IntStream.rangeClosed(1, 40)
                .mapToObj(i -> chunk(i, "章节" + i, "字".repeat(200))).toList());
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean(), any()))
                .thenReturn(extractJson(1));

        assertDoesNotThrow(() -> service.extract(message()),
                "容量拒止只降级概览轮，整次提取照常走完逐组原文面");
        assertTrue(summaryRequests().isEmpty(), "降级=概览轮零摘要调用（事前 plan 判定仍在位）");
        verify(coreGateway).writeExtractionResult(eq(DOC_ID), any(), eq(TOKEN));
        verify(coreGateway, never()).markExtractionFailed(anyLong(), any(), eq(TOKEN));
    }

 
    @Test
    void 提取整包超模型上限_零发出failFast() {
        stubRoute(endpoint(150));
        stubDefaults();
        stubChunks(List.of(
                chunk(1, "A", "系统应支持登录。"),
                chunk(2, "B", "系统应支持登出。")));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.extract(message()));
        assertEquals(com.sixbean.testpike.common.exception.BizErrorCode.AI_PROMPT_TOO_LARGE,
                ex.getCode());
        assertTrue(ex.getMessage().contains("上限") && ex.getMessage().contains("150"),
                ex.getMessage());
        assertEquals(0, captureRequests().size(), "超限=提取调用零发出（不烧供应商）");
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(coreGateway).markExtractionFailed(eq(DOC_ID), reason.capture(), eq(TOKEN));
        assertTrue(reason.getValue().contains("上限"), reason.getValue());
    }

    
    @Test
    void 模型上限null_仅文档预算() {
        stubRoute(endpoint(null));
        stubDefaults();
        stubChunks(List.of(chunk(1, "A", "系统应支持登录。")));
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean(), any()))
                .thenReturn(extractJson(1));

        service.extract(message());

        assertEquals(1, captureRequests().size());
        verify(coreGateway, times(1)).writeExtractionResult(eq(DOC_ID), any(), eq(TOKEN));
    }

    /** discriminator: the compression decision plane reads only
 * the adaptive window (catalog limit x safety factor); the static
 * ai.doc.token-budget key must never be consulted here. Old wiring took
 * fromConfig("20000") clamped to the 2000 limit -> a 300-token chunk fits
 * and sends raw (zero summaries = red). New wiring computes
 * effectiveTokens(2000,"0.1")=200 -> 300 > 200 forces the Map summary. */
    @Test
    void 自适应预算系数生效_静态键退出决策面() {
        stubRoute(endpoint(2000));
        stubConfigs("0.1", null, null, null, null, null);
        // Static-key stub: under the new wiring it must never be read.
        when(coreGateway.getSysConfig(ContextBudgetPlanner.BUDGET_CONFIG_KEY)).thenReturn("20000");
        stubChunks(List.of(chunk(1, "A", "字".repeat(300)))); // 300 tokens probed: 200 < 300 <= 2000
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    ChatCompletionRequest req = inv.getArgument(0);
                    return req.messages().get(0).content().contains(LlmExtractMarkers.DOC_EXTRACT_SUMMARY)
                            ? summary("摘要") : extractJson(1);
                });

        service.extract(message());

        assertEquals(1, summaryRequests().size(), "budget 2000 x 0.1 must push the 300-token chunk into the summary round");
        verify(coreGateway, never()).getSysConfig(ContextBudgetPlanner.BUDGET_CONFIG_KEY);
    }
}
