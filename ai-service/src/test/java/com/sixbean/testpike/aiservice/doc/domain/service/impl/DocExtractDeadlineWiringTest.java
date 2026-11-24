package com.sixbean.testpike.aiservice.doc.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.doc.domain.component.ExtractDeadline;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.AiDocExtractMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.sixbean.testpike.aiservice.doc.domain.service.impl.DocExtractTestFixtures.endpoint;

/**
 * F2 extraction task-level deadline wiring .
 * <p>Pinned here: the run reads the K8 window key once at entry, every LLM call
 * through the fallback chain receives a clamp that shortens the per-call timeout
 * to the remaining window, a missing key falls back to the seeded default so the
 * clamp never fires inside a healthy 7200s budget, and an exhausted window
 * (clamp throwing AI_TIMEOUT on the chain) writes the failure back through the
 * normal retry-able path instead of leaving the document stuck.
 */
class DocExtractDeadlineWiringTest {

    private static final long DOC_ID = 7L;
    private static final long PROJECT_ID = 3L;
    private static final String TOKEN = "deadline-token-001";

    private final DocExtractTestFixtures fixtures = new DocExtractTestFixtures(DOC_ID, PROJECT_ID, TOKEN);
    private final CoreGateway coreGateway = fixtures.coreGateway();
    private final LlmFallbackChain llmChain = fixtures.llmChain();
    private final ProjectLlmRouter llmRouter = fixtures.llmRouter();
    private final DocExtractServiceImpl service = fixtures.pipeline();

    private void stubHappyChain() {
        fixtures.stubHappyChain();
    }

    /** K8 window 120s → the chain receives a clamp that caps a 300s call to the window. */
    @Test
    void 窗口生效时clamp把单次超时钳到剩余窗口() {
        stubHappyChain();
        when(coreGateway.getSysConfig(ExtractDeadline.CONFIG_KEY)).thenReturn("120");

        service.extract(new AiDocExtractMessage(DOC_ID, PROJECT_ID, 11L, null, TOKEN));

        Duration clamped = fixtures.capturedClamp().apply(Duration.ofSeconds(300));
        assertTrue(clamped.toMillis() > 0 && clamped.toMillis() <= 120_000L,
                "剩余 ≤120s 窗口必须钳短 300s 超时, 实际=" + clamped);
        verify(coreGateway).writeExtractionResult(eq(DOC_ID), any(), eq(TOKEN));
    }

    /** Key absent → shipped 7200s default; a 300s call stays untouched (other callers' semantics intact). */
    @Test
    void 缺键回退7200缺省_窗口内超时不被钳短() {
        stubHappyChain();
        when(coreGateway.getSysConfig(ExtractDeadline.CONFIG_KEY)).thenReturn(null);

        service.extract(new AiDocExtractMessage(DOC_ID, PROJECT_ID, 11L, null, TOKEN));

        assertEquals(Duration.ofSeconds(300), fixtures.capturedClamp().apply(Duration.ofSeconds(300)),
                "7200s 缺省窗口内 300s 调用零改动");
    }

    /** Exhausted window: the chain-side clamp throws AI_TIMEOUT → failure written back retry-ably. */
    @Test
    void 窗口耗尽_AI_TIMEOUT经失败回写路径人话可重试() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(List.of(
                Map.of("seq", 1, "chapter", "登录", "content", "账号密码正确可登录。")));
        when(llmChain.chatStreamHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                any(), any()))
                .thenThrow(new ServiceException("需求点提取超出时长上限，请重试", BizErrorCode.AI_TIMEOUT));
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean(), any()))
                .thenThrow(new ServiceException("需求点提取超出时长上限，请重试", BizErrorCode.AI_TIMEOUT));

        assertThrows(ServiceException.class,
                () -> service.extract(new AiDocExtractMessage(DOC_ID, PROJECT_ID, 11L, null, TOKEN)));

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(coreGateway).markExtractionFailed(eq(DOC_ID), reason.capture(), eq(TOKEN));
        assertTrue(reason.getValue().contains("需求点提取失败"),
                "回写须走既有失败人话路径, 实际=" + reason.getValue());
        verify(coreGateway, never()).writeExtractionResult(eq(DOC_ID), any(), any());
    }
}
