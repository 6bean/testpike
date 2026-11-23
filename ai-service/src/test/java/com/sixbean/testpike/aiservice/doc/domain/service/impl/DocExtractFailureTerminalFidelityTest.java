package com.sixbean.testpike.aiservice.doc.domain.service.impl;

import com.sixbean.testpike.aiservice.doc.domain.component.ExtractFidelityLedger;
import com.sixbean.testpike.aiservice.doc.domain.component.ExtractFidelityLedger.Entry;
import com.sixbean.testpike.aiservice.doc.domain.component.ExtractFidelityLedgerStore;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * remediation (failure-terminal face): a generation that burned plane
 * calls and then failed must still land its fidelity row. The live window
 * proved the gap — token 1cfbfa2f booked seven extraction-plane calls, died
 * on the task-length budget, and left the ledger blind (the audit gate reads
 * SEAM-BLIND: booked without any row). The failure face therefore books a
 * zero-item row through the absent-only door, so a generation that already
 * landed its richer success row can never be overwritten by the failure
 * bookkeeping (R28 posture); the run's own failure semantics stay untouched.
 */
class DocExtractFailureTerminalFidelityTest {

    private static final long DOC_ID = 13L;
    private static final long PROJECT_ID = 8L;
    private static final String TOKEN = "fail-terminal-fidelity-token-01";

    private final DocExtractTestFixtures fixtures =
            new DocExtractTestFixtures(DOC_ID, PROJECT_ID, TOKEN);
    private final ExtractFidelityLedgerStore store =
            mock(ExtractFidelityLedgerStore.class);

    /** A plane call that dies mid-run books the zero-item terminal row with
     *  the generation identity, and the run still fails exactly as before. */
    @Test
    void failureAfterPlaneCall_booksZeroItemTerminalRow() {
        fixtures.stubRoutedChunks();
        DocExtractServiceImpl service = fixtures.pipeline(fixtures.timeoutResolver());
        service.setFidelityLedgerStore(store);
        when(fixtures.llmChain().chatStreamHandle(any(), eq(PROJECT_ID), any(), any(), any()))
                .thenThrow(new ServiceException("供应商调用超时", 50301));
        when(fixtures.llmChain().chat(any(), eq(PROJECT_ID), any(),
                org.mockito.ArgumentMatchers.anyBoolean(), any()))
                .thenThrow(new ServiceException("供应商调用超时", 50301));

        assertThrows(ServiceException.class, () -> service.extract(fixtures.message()));

        ArgumentCaptor<Entry> captor = ArgumentCaptor.forClass(Entry.class);
        verify(store).recordIfAbsent(captor.capture());
        Entry row = captor.getValue();
        assertEquals(DOC_ID, row.refId());
        assertEquals(TOKEN, row.runId());
        assertEquals(ExtractFidelityLedger.TASK_DOC_EXTRACT, row.taskType());
        assertEquals(0, row.itemCount());
    }

    /** A run that dies before any plane call still lands the honest zero
     *  face (chunks read, nothing injected) — the row exists, the numbers
     *  stay what actually happened, nothing is fabricated. */
    @Test
    void failureBeforeAnyPlaneCall_booksZeroFaceRow() {
        fixtures.stubRoutedChunks();
        when(fixtures.coreGateway().getSysConfig(
                com.sixbean.testpike.aiservice.doc.domain.component
                        .ExtractDeadline.CONFIG_KEY))
                .thenThrow(new ServiceException("core 网关不可达", 50300));
        DocExtractServiceImpl service = fixtures.pipeline(fixtures.timeoutResolver());
        service.setFidelityLedgerStore(store);

        assertThrows(ServiceException.class, () -> service.extract(fixtures.message()));

        ArgumentCaptor<Entry> captor = ArgumentCaptor.forClass(Entry.class);
        verify(store).recordIfAbsent(captor.capture());
        Entry row = captor.getValue();
        assertEquals(0, row.injectedTokens());
        assertEquals(0, row.itemCount());
        assertEquals(1, row.chunkCount());
    }

    /** The success face keeps its richer upsert path — the absent-only door
     *  is exclusively the failure bookkeeping, never a replacement. */
    @Test
    void successFace_stillLandsUpsertNotTheAbsentDoor() {
        fixtures.stubRoutedChunks();
        fixtures.stubPlaneTransports(inv -> new com.sixbean.testpike.aiservice.llm.domain
                .LlmChatResult(DocExtractTestFixtures.VALID_JSON,
                "openai", "routed-model", 10, 5, 2L));
        DocExtractServiceImpl service = fixtures.pipeline(fixtures.timeoutResolver());
        service.setFidelityLedgerStore(store);

        service.extract(fixtures.message());

        verify(store).upsertIfChanged(any(Entry.class));
        verify(store, never()).recordIfAbsent(any(Entry.class));
    }

    /** Legacy assembly without the ledger door: the failure path stays
     *  crash-free (bookkeeping can never fail the run it observes). */
    @Test
    void absentLedgerDoor_failurePathStaysClean() {
        fixtures.stubRoutedChunks();
        DocExtractServiceImpl service = fixtures.pipeline(fixtures.timeoutResolver());
        when(fixtures.llmChain().chatStreamHandle(any(), eq(PROJECT_ID), any(), any(), any()))
                .thenThrow(new ServiceException("供应商调用超时", 50301));
        when(fixtures.llmChain().chat(any(), eq(PROJECT_ID), any(),
                org.mockito.ArgumentMatchers.anyBoolean(), any()))
                .thenThrow(new ServiceException("供应商调用超时", 50301));

        assertThrows(ServiceException.class, () -> service.extract(fixtures.message()));
    }
}
