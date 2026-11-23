package com.sixbean.testpike.aiservice.doc.domain.service.impl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.doc.domain.component.DocExtractLedgerStore;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Single-source fixtures shared by the DocExtract L2 suites. Extracted when
 * the third identical copy of the logback capture appeared (duplicate-logic
 * rule: shared at two or more consumers). Keeps each suite to a one-line
 * wiring so the capture/detach semantics stay in exactly one place.
 */
final class DocExtractTestSupport {

    private DocExtractTestSupport() {
    }

    /** Attach a fresh ListAppender to the DocExtractServiceImpl logger. */
    static ListAppender<ILoggingEvent> attachServiceLogCapture() {
        Logger logger = (Logger) LoggerFactory.getLogger(DocExtractServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    /** Detach the capture previously added by attachServiceLogCapture. */
    static void detachServiceLogCapture(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(DocExtractServiceImpl.class);
        logger.detachAppender(appender);
    }

    /** Service under test wired with the suite's own collaborator mocks. */
    static DocExtractServiceImpl wiredService(CoreGateway coreGateway,
                                              LlmFallbackChain llmChain,
                                              ProjectLlmRouter llmRouter,
                                              DocExtractLedgerStore ledgerStore) {
        DocExtractServiceImpl impl = new DocExtractServiceImpl(
                coreGateway, llmChain, llmRouter, new ObjectMapper());
        impl.setExtractLedgerStore(ledgerStore);
        return impl;
    }

    /** Item payload handed to the final core write-back for one document. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> capturedWrite(CoreGateway coreGateway,
                                                   long docId, String token) {
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(coreGateway).writeExtractionResult(eq(docId), captor.capture(), eq(token));
        return captor.getValue();
    }
}
