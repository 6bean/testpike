package com.sixbean.testpike.aiservice.doc.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.AiDocExtractMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.sixbean.testpike.aiservice.doc.domain.service.impl.DocExtractTestFixtures.endpoint;
import static com.sixbean.testpike.aiservice.doc.domain.service.impl.DocExtractTestFixtures.chunk;
import static com.sixbean.testpike.aiservice.doc.domain.service.impl.DocExtractTestFixtures.VALID_JSON;

/**
 * AP-4(1) requirement-extraction regeneration loop .
 * <p>Mirrors the AiGen stage-1 retryFeedback contract: a malformed or truncated model
 * output is no longer a whole-job failure — the error list is folded back into the next
 * user prompt and the job regenerates up to 2 times before failing honestly.
 * <p>L2 isolation only on infrastructure seams (CoreGateway HTTP client, LlmFallbackChain
 * provider call, ProjectLlmRouter) — the loop logic itself is exercised for real.
 */
class DocExtractRegenerationLoopTest {

    private static final long DOC_ID = 91L;
    private static final long PROJECT_ID = 31L;
    private static final String TOKEN = "gen-token-regen-001";
    private final DocExtractTestFixtures fixtures = new DocExtractTestFixtures(DOC_ID, PROJECT_ID, TOKEN);
    private final CoreGateway coreGateway = fixtures.coreGateway();
    private final LlmFallbackChain llmChain = fixtures.llmChain();
    private final ProjectLlmRouter llmRouter = fixtures.llmRouter();
    private final DocExtractServiceImpl service = fixtures.pipeline();

    private void stubChunks() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getDocumentChunks(DOC_ID))
                .thenReturn(List.of(chunk(1, "登录 > 异常", "连续五次输入错误密码，账号锁定十五分钟。")));
    }

    private List<String> capturedUserPrompts(int expectedCalls) {
        ArgumentCaptor<ChatCompletionRequest> req =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmChain, times(expectedCalls)).chat(req.capture(), eq(PROJECT_ID),
                any(LlmFallbackNotice.class), anyBoolean(), any());
        return req.getAllValues().stream()
                .map(r -> r.messages().get(1).content()).toList();
    }

    /** Malformed first output: the error list is folded back and the second call wins. */
    @Test
    void 首次输出非法JSON_错误清单拼回重生成_第二次成功回写() {
        stubChunks();
        fixtures.stubLlmResponses("抱歉，我无法完成该任务。", VALID_JSON);

        service.extract(fixtures.message());

        List<String> prompts = capturedUserPrompts(2);
        assertFalse(prompts.get(0).contains("上次输出格式不合格"),
                "第一次调用不带反馈段（基线零漂移），实际=" + prompts.get(0));
        assertTrue(prompts.get(1).contains("上次输出格式不合格"),
                "第二次调用 user prompt 必须拼回错误清单，实际=" + prompts.get(1));
        assertTrue(prompts.get(1).contains("不是合法 JSON"),
                "反馈须含真实解析错误摘要，实际=" + prompts.get(1));
        verify(coreGateway).writeExtractionResult(eq(DOC_ID), any(), eq(TOKEN));
        verify(coreGateway, never()).markExtractionFailed(eq(DOC_ID), any(), any());
    }

    /** A truncated JSON payload is the same failure class: it re-enters the loop. */
    @Test
    void 截断输出_同样进入重生成回路() {
        stubChunks();
        fixtures.stubLlmResponses("{\"requirements\":[{\"title\":\"登录支", VALID_JSON);

        service.extract(fixtures.message());

        List<String> prompts = capturedUserPrompts(2);
        assertTrue(prompts.get(1).contains("上次输出格式不合格"), "截断轮必须拼回反馈");
        verify(coreGateway).writeExtractionResult(eq(DOC_ID), any(), eq(TOKEN));
        verify(coreGateway, never()).markExtractionFailed(eq(DOC_ID), any(), any());
    }

    /** Feedback accumulates: the third prompt carries every previous rejection reason. */
    @Test
    void 连续两次不合格_第三次prompt含累积反馈清单() {
        stubChunks();
        fixtures.stubLlmResponses("输出不是 JSON 的散文",
                "{\"requirements\":[{\"acceptanceCriteria\":\"缺标题也要拒\"}]}", VALID_JSON);

        service.extract(fixtures.message());

        List<String> prompts = capturedUserPrompts(3);
        assertTrue(prompts.get(2).contains("不是合法 JSON") || prompts.get(2).contains("缺少需求点清单"),
                "首轮格式错误必须在累积反馈中，实际=" + prompts.get(2));
        assertTrue(prompts.get(2).contains("缺少标题"),
                "次轮条目错误必须在累积反馈中，实际=" + prompts.get(2));
        verify(coreGateway).writeExtractionResult(eq(DOC_ID), any(), eq(TOKEN));
    }

    /** Regeneration cap = 2: after three rejected outputs the job fails honestly (50003). */
    @Test
    void 重生成封顶两次仍不合格_整单失败回写含已重试() {
        stubChunks();
        fixtures.stubLlmResponses("坏输出一", "坏输出二", "坏输出三");

        ServiceException ex = assertThrows(ServiceException.class, () -> service.extract(fixtures.message()));

        assertTrue(ex.getMessage().contains("已重试 2 次"),
                "失败文案必须点名重试上限，实际=" + ex.getMessage());
        assertEquals(BizErrorCode.AI_FORMAT_ERROR, ex.getCode(), "终态错误码=50003");
        capturedUserPrompts(3);
        verify(coreGateway).markExtractionFailed(eq(DOC_ID), contains("提取"), eq(TOKEN));
        verify(coreGateway, never()).writeExtractionResult(eq(DOC_ID), any(), any());
    }

    /** Baseline pin: a first-time valid output still costs exactly one provider call. */
    @Test
    void 一次成功零漂移_仅调用一次LLM() {
        stubChunks();
        fixtures.stubLlmResponses(VALID_JSON);

        service.extract(fixtures.message());

        verify(llmChain, times(1)).chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                anyBoolean(), any());
        verify(coreGateway).writeExtractionResult(eq(DOC_ID), any(), eq(TOKEN));
    }
}
