package com.sixbean.testpike.aiservice.doc.domain.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.doc.domain.component.ExtractDeadline;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.AiDocExtractMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Failure write-back noise posture of the extraction task (R53 log-noise gate):
 * when the extraction fails because the document row is already gone (user
 * deleted it mid-run — a legitimate race), the failure write-back can never
 * land and its core 404 rejection is the EXPECTED outcome, not an incident.
 * Such write-back rejections must be recognised through the single entity-gone
 * vocabulary (common EntityGoneSignals, R40 — no second token list) and logged
 * at WARN, never ERROR: nine ERROR lines from a repeated MQ retry of one
 * deleted document tripped the new-signature red of the log-noise ledger.
 * <p>The teeth case keeps the ERROR for genuine write-back outages (core
 * unreachable), so the demotion cannot swallow a real persistence failure.
 * <p>L2 posture: CoreGateway / router / chain are infrastructure mocks (same
 * face as DocExtractReconcileTest); real chain behaviour lives at L4.
 */
class DocExtractEntityGoneWriteBackTest {

    private static final long DOC_ID = 41L;
    private static final long PROJECT_ID = 3L;
    private static final String TOKEN = "gen-token-entity-gone";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
    private final DocExtractServiceImpl service = new DocExtractServiceImpl(
            coreGateway, llmChain, llmRouter, new ObjectMapper());

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogCapture() {
        logAppender = DocExtractTestSupport.attachServiceLogCapture();
    }

    @AfterEach
    void detachLogCapture() {
        DocExtractTestSupport.detachServiceLogCapture(logAppender);
    }

    private static AiDocExtractMessage message() {
        return new AiDocExtractMessage(DOC_ID, PROJECT_ID, 11L, null, TOKEN);
    }

    /** Any core read inside the run may surface the entity-gone rejection;
     *  the deadline key is the first door the try block touches. */
    private void routeOkAndChunksPresent() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(new LlmEndpointConfig(
                "openai", "routed-model", "dummy-key", "http://localhost:4000/v1",
                LlmEndpointConfig.SOURCE_CONFIG, 2000));
        when(coreGateway.getDocumentChunks(DOC_ID))
                .thenReturn(List.of(Map.of("id", 1, "content", "需求：登录成功后返回令牌")));
    }

    @Test
    void deletedDocumentWriteBackRejectionStaysBelowErrorLevel() {
        routeOkAndChunksPresent();
        ServiceException gone = new ServiceException("文档不存在（或已删除）", BizErrorCode.NOT_FOUND);
        when(coreGateway.getSysConfig(ExtractDeadline.CONFIG_KEY)).thenThrow(gone);
        // the write-back carries failExtraction's human-readable prefix
        doThrow(gone).when(coreGateway)
                .markExtractionFailed(anyLong(), eq("需求点提取失败：" + gone.getMessage()), eq(TOKEN));

        assertThrows(ServiceException.class, () -> service.extract(message()));

        long errorLines = logAppender.list.stream()
                .filter(e -> e.getLevel().equals(Level.ERROR)).count();
        assertEquals(0, errorLines,
                "entity-gone write-back rejection must not log ERROR, got: " + logAppender.list);
        boolean honestWarn = logAppender.list.stream().anyMatch(e ->
                e.getLevel().equals(Level.WARN) && e.getFormattedMessage().contains("回写跳过"));
        assertTrue(honestWarn, "a WARN naming the skipped write-back must remain (no silent drop)");
    }

    @Test
    void genuineWriteBackOutageStillLogsError() {
        routeOkAndChunksPresent();
        ServiceException original = new ServiceException("文档不存在（或已删除）", BizErrorCode.NOT_FOUND);
        when(coreGateway.getSysConfig(ExtractDeadline.CONFIG_KEY)).thenThrow(original);
        doThrow(new RuntimeException("core 连接拒绝")).when(coreGateway)
                .markExtractionFailed(anyLong(),
                        eq("需求点提取失败：" + original.getMessage()), eq(TOKEN));

        assertThrows(ServiceException.class, () -> service.extract(message()));

        long errorLines = logAppender.list.stream()
                .filter(e -> e.getLevel().equals(Level.ERROR)
                        && e.getFormattedMessage().contains("提取失败回写异常")).count();
        assertEquals(1, errorLines, "a real write-back outage keeps its ERROR signature");
    }

    @Test
    void extractionStillRethrowsAfterSkippedWriteBack() {
        routeOkAndChunksPresent();
        ServiceException gone = new ServiceException("文档不存在（或已删除）", BizErrorCode.NOT_FOUND);
        when(coreGateway.getSysConfig(ExtractDeadline.CONFIG_KEY)).thenThrow(gone);
        doThrow(gone).when(coreGateway)
                .markExtractionFailed(anyLong(), eq("需求点提取失败：" + gone.getMessage()), eq(TOKEN));

        // The consumer guard upstream decides ack-vs-dead-letter from the rethrown
        // exception; swallowing it here would hide the failure from the MQ audit.
        ServiceException rethrown = assertThrows(ServiceException.class, () -> service.extract(message()));
        assertTrue(rethrown.getMessage().contains("文档不存在"), "original failure travels on unchanged");
    }
}
