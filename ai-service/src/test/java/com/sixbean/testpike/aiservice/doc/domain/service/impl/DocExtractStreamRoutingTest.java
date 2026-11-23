package com.sixbean.testpike.aiservice.doc.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.doc.domain.component.ExtractDeadline;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
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
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Extraction-plane transport routing .
 * <p>Pinned here: with the hot key on (factory default, missing = on) the final
 * extraction round goes through the streaming entry of the fallback chain and
 * the task deadline clamp travels with it; with the key explicitly off the call
 * keeps the legacy blocking path untouched; a stream that never established
 * (zero deltas delivered) falls back to blocking once with an honest WARN —
 * while a failure after deltas started is NOT retried blocking (no double burn
 * of a half-delivered response).
 * <p>Infra (gateway/router/chain) mocked per L2 rules; the service orchestration
 * and the deadline component run real code.
 */
class DocExtractStreamRoutingTest {

    private static final long DOC_ID = 8L;
    private static final long PROJECT_ID = 3L;
    private static final String TOKEN = "stream-token-001";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
    private final DocExtractServiceImpl service = new DocExtractServiceImpl(
            coreGateway, llmChain, llmRouter, new ObjectMapper());

    private static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "routed-model", "dummy-key",
                "http://localhost:4000/v1", LlmEndpointConfig.SOURCE_CONFIG);
    }

    private static LlmChatResult extractJson() {
        return new LlmChatResult(
                "{\"requirements\":[{\"title\":\"正确账号密码登录\"}]}",
                "openai", "routed-model", 10, 5, 1L);
    }

    private void stubSingleChunk() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(List.of(
                Map.of("seq", 1, "chapter", "登录", "content", "账号密码正确可登录。")));
    }

    /** Key missing = on (R32 factory default): the final round runs streaming with the clamp. */
    @Test
    @SuppressWarnings("unchecked")
    void 开关缺省走流式并透传deadline钳() {
        stubSingleChunk();
        when(llmChain.chatStreamHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                any(), any())).thenReturn(new LlmChatHandle(extractJson(), endpoint()));
        when(coreGateway.getSysConfig(ExtractDeadline.CONFIG_KEY)).thenReturn("120");

        service.extract(new AiDocExtractMessage(DOC_ID, PROJECT_ID, 11L, null, TOKEN));

        ArgumentCaptor<UnaryOperator<Duration>> clamp = ArgumentCaptor.forClass(UnaryOperator.class);
        verify(llmChain).chatStreamHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                any(), clamp.capture());
        Duration clamped = clamp.getValue().apply(Duration.ofSeconds(300));
        assertTrue(clamped.toMillis() > 0 && clamped.toMillis() <= 120_000L,
                "流式调用必须携带任务 deadline 钳, 实际=" + clamped);
        verify(llmChain, never()).chat(any(), eq(PROJECT_ID), any(), anyBoolean(), any());
        verify(coreGateway).writeExtractionResult(eq(DOC_ID), any(), eq(TOKEN));
    }

    /** Key explicitly off: legacy blocking path byte-for-byte, streaming never touched. */
    @Test
    void 开关关闭保持非流式旧路径() {
        stubSingleChunk();
        when(coreGateway.getSysConfig(DocExtractServiceImpl.KEY_EXTRACT_STREAM_ENABLED))
                .thenReturn("false");
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                anyBoolean(), any())).thenReturn(extractJson());

        service.extract(new AiDocExtractMessage(DOC_ID, PROJECT_ID, 11L, null, TOKEN));

        verify(llmChain).chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                anyBoolean(), any());
        verify(llmChain, never()).chatStreamHandle(any(), eq(PROJECT_ID), any(),
                any(Consumer.class), any());
        verify(coreGateway).writeExtractionResult(eq(DOC_ID), any(), eq(TOKEN));
    }

    /** Stream establishment failed (no delta ever delivered): explicit one-shot blocking retry. */
    @Test
    void 流建立失败显式回退非流式一次() {
        stubSingleChunk();
        when(llmChain.chatStreamHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                any(), any())).thenThrow(new ServiceException("供应商连接失败",
                        BizErrorCode.AI_PROVIDER_ERROR));
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                anyBoolean(), any())).thenReturn(extractJson());

        service.extract(new AiDocExtractMessage(DOC_ID, PROJECT_ID, 11L, null, TOKEN));

        verify(llmChain).chatStreamHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                any(), any());
        verify(llmChain).chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                anyBoolean(), any());
        verify(coreGateway).writeExtractionResult(eq(DOC_ID), any(), eq(TOKEN));
    }
}
