package com.sixbean.testpike.aiservice.doc.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmProperties;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmTimeoutResolver;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.AiDocExtractMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.sixbean.testpike.aiservice.doc.domain.service.impl.DocExtractTestFixtures.endpoint;

/**
 * every extraction-plane model call lands a tp_ai_call_log
 * row keyed by the generation token, so the double-burn audit can
 * reconcile burns per generation.
 * <p>Pinned here: a successful plane call records provider/model/tokens and
 * the real latency with a null error code; a failed plane call records a
 * zero-token row with the measured elapsed time and the error code (the
 * numeric ServiceException code, else the exception simple name). Rows land
 * at the single extraction-plane seam (final round / group summary / gap
 * ask) — no second accounting path. A service assembled without the writer
 * (legacy fixtures) stays byte-identical: no ledger door = no rows, no crash.
 */
class DocExtractPlaneCallLedgerTest {

    private static final long DOC_ID = 9L;
    private static final long PROJECT_ID = 4L;
    private static final String TOKEN = "plane-ledger-token-001";

    private final DocExtractTestFixtures fixtures = new DocExtractTestFixtures(DOC_ID, PROJECT_ID, TOKEN);
    private final CoreGateway coreGateway = fixtures.coreGateway();
    private final LlmFallbackChain llmChain = fixtures.llmChain();
    private final ProjectLlmRouter llmRouter = fixtures.llmRouter();
    private final LlmTimeoutResolver timeoutResolver = fixtures.timeoutResolver();
    private final DocExtractServiceImpl service = fixtures.pipeline(timeoutResolver);
    private final com.sixbean.testpike.aiservice.llm.domain.component.AiCallLogWriter writer =
            mock(com.sixbean.testpike.aiservice.llm.domain.component.AiCallLogWriter.class);

    private void stubSingleChunkDoc() {
        fixtures.stubRoutedChunks();
    }

    /** A successful final-extraction call lands one full row: provider/model,
     *  the reported tokens and latency, the generation token, null error. */
    @Test
    void successPlaneCall_landsLedgerRowWithGenerationToken() {
        stubSingleChunkDoc();
        service.setAiCallLogWriter(writer);
        LlmChatResult extractResult = new LlmChatResult(
                "{\"requirements\":[{\"title\":\"正确账号密码登录\"}]}",
                "openai", "routed-model", 10, 5, 1200L);
        when(llmChain.chatStreamHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                any(), any())).thenReturn(new LlmChatHandle(extractResult, endpoint()));

        service.extract(new AiDocExtractMessage(DOC_ID, PROJECT_ID, 11L, null, TOKEN));

        verify(writer).insert(eq(PROJECT_ID), eq("openai"), eq("routed-model"),
                eq(DocExtractServiceImpl.FUNCTION_EXTRACT_PLANE),
                eq(10), eq(5), eq(1200L), eq(TOKEN), isNull());
    }

    /** A failed plane call lands a zero-token row with the measured elapsed
 * time and the numeric error code — the burn happened, cost is honestly
 * unknown (0), and must see it per generation. */
    @Test
    void failedPlaneCall_landsZeroTokenRowWithErrorCode() {
        stubSingleChunkDoc();
        service.setAiCallLogWriter(writer);
        when(llmChain.chatStreamHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                any(), any())).thenThrow(new ServiceException("供应商调用超时", 50301));
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                anyBoolean(), any())).thenThrow(new ServiceException("供应商调用超时", 50301));

        assertThrows(ServiceException.class,
                () -> service.extract(new AiDocExtractMessage(DOC_ID, PROJECT_ID, 11L, null, TOKEN)));

        verify(writer).insert(eq(PROJECT_ID), isNull(), eq("routed-model"),
                eq(DocExtractServiceImpl.FUNCTION_EXTRACT_PLANE),
                eq(0), eq(0), anyLong(), eq(TOKEN), eq("50301"));
    }

    /** A non-ServiceException outage records the exception simple name as the
     *  error code — the row stays classifiable without inventing a code. */
    @Test
    void runtimeOutage_landsRowWithExceptionSimpleName() {
        stubSingleChunkDoc();
        service.setAiCallLogWriter(writer);
        when(llmChain.chatStreamHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                any(), any())).thenThrow(new IllegalStateException("stream broke"));
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                anyBoolean(), any())).thenThrow(new IllegalStateException("stream broke"));

        assertThrows(RuntimeException.class,
                () -> service.extract(new AiDocExtractMessage(DOC_ID, PROJECT_ID, 11L, null, TOKEN)));

        verify(writer).insert(eq(PROJECT_ID), isNull(), eq("routed-model"),
                eq(DocExtractServiceImpl.FUNCTION_EXTRACT_PLANE),
                anyInt(), anyInt(), anyLong(), eq(TOKEN), eq("IllegalStateException"));
    }

    /** Legacy assembly without the writer door: extraction behaves exactly as
     *  before (no crash, no ledger), pinned by the zero-writer extract call. */
    @Test
    void writerAbsent_zeroDriftLegacyPath() {
        stubSingleChunkDoc();
        LlmChatResult extractResult = new LlmChatResult(
                "{\"requirements\":[{\"title\":\"正确账号密码登录\"}]}",
                "openai", "routed-model", 10, 5, 1L);
        when(llmChain.chatStreamHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                any(), any())).thenReturn(new LlmChatHandle(extractResult, endpoint()));

        service.extract(new AiDocExtractMessage(DOC_ID, PROJECT_ID, 11L, null, TOKEN));

        verify(coreGateway).writeExtractionResult(eq(DOC_ID), any(), eq(TOKEN));
    }
}
