package com.sixbean.testpike.aiservice.doc.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.doc.domain.component.ExtractFidelityLedger;
import com.sixbean.testpike.aiservice.doc.domain.component.ExtractFidelityLedgerStore;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.AiDocExtractMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DD batch item ㊀ (service seam face): the terminal hook must route through
 * the SINGLE failure write-back source — the same human-readable text
 * composition and the same absent-door fidelity book the in-run catch faces
 * use — instead of a second spelling of the failure vocabulary in the mq
 * layer (R40).
 * <p>Pinned contracts: ① a human-readable extract_error flips the document
 * out of the extracting status, ② the failed generation keeps its zero-item
 * fidelity row (audit gate must not read a burned run as seam-blind), ③ both
 * doors reference the message's generation token — the exact identity the
 * dead-letter ledger row carries, so a cross-check finds the same run on both
 * sides. An entity-gone cause short-circuits the whole hook (a deleted
 * document has no terminal row and its dead ledger message must not be
 * forced in), and an outage on either door never escapes to the caller.
 */
class DocExtractTerminalFailureWritebackServiceTest {

    private static final long DOC_ID = 970_000_201L;
    private static final long PROJECT_ID = 3L;
    private static final String TOKEN = "term-svc-gen-token";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final ExtractFidelityLedgerStore fidelityStore = mock(ExtractFidelityLedgerStore.class);
    private final DocExtractServiceImpl service = new DocExtractServiceImpl(
            coreGateway, mock(LlmFallbackChain.class), mock(ProjectLlmRouter.class),
            new ObjectMapper());

    private AiDocExtractMessage message() {
        return new AiDocExtractMessage(DOC_ID, PROJECT_ID, 11L, null, TOKEN);
    }

    private DocExtractServiceImpl withLedgerDoor() {
        service.setFidelityLedgerStore(fidelityStore);
        return service;
    }

    @Test
    void ordinaryEscapingFailureLandsHumanReadableWriteBackAndZeroItemFidelityRow() {
        when(fidelityStore.recordIfAbsent(any())).thenReturn(true);

        withLedgerDoor().handleTerminalFailure(message(),
                new IllegalStateException("provider gateway silent 502"));

        // ① human-readable reason + ③ the generation token the dead-letter row carries
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(coreGateway).markExtractionFailed(eq(DOC_ID), reason.capture(), eq(TOKEN));
        assertTrue(reason.getValue().startsWith("需求点提取失败："),
                "terminal text must reuse the single failure vocabulary, was: " + reason.getValue());
        assertTrue(reason.getValue().contains("provider gateway silent 502"),
                "the escaping cause must stay readable in the text, was: " + reason.getValue());

        // ② the failed generation keeps its book: zero items, same identity
        ArgumentCaptor<ExtractFidelityLedger.Entry> row =
                ArgumentCaptor.forClass(ExtractFidelityLedger.Entry.class);
        verify(fidelityStore).recordIfAbsent(row.capture());
        assertEquals(DOC_ID, row.getValue().refId());
        assertEquals(TOKEN, row.getValue().runId());
        assertEquals(0, row.getValue().itemCount());
    }

    @Test
    void deletedDocumentCauseShortCircuitsWithoutTouchingEitherDoor() {
        withLedgerDoor();
        ServiceException gone = new ServiceException("文档不存在（或已删除）", BizErrorCode.NOT_FOUND);

        service.handleTerminalFailure(message(), gone);

        verify(coreGateway, never()).markExtractionFailed(anyLong(), any(), any());
        verify(fidelityStore, never()).recordIfAbsent(any());
    }

    @Test
    void doorOutagesNeverEscapeTheTerminalHook() {
        when(fidelityStore.recordIfAbsent(any())).thenReturn(false);
        doThrow(new IllegalStateException("core unreachable")).when(coreGateway)
                .markExtractionFailed(anyLong(), any(), any());

        assertDoesNotThrow(() -> service.handleTerminalFailure(message(),
                new IllegalStateException("provider exploded")));
    }

    @Test
    void assemblyWithoutTheLedgerDoorStillWritesBackTheStatus() {
        service.handleTerminalFailure(message(), new IllegalStateException("locked door gone"));

        verify(coreGateway).markExtractionFailed(eq(DOC_ID),
                contains("需求点提取失败："), eq(TOKEN));
    }
}
