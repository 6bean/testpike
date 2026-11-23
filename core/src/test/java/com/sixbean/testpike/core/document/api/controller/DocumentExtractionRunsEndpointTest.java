package com.sixbean.testpike.core.document.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.document.domain.service.DocumentExtractionService;
import com.sixbean.testpike.core.events.api.dto.TaskEventRunRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Document extraction tail revisit door (R67): the preview dialog answers an
 * empty cursor window with the run-history aggregation so "frames aged out of
 * retention" stays distinguishable from "this run produced nothing". The door
 * delegates whole to the shared run-history reader (same shape as the batch
 * and explore references, R40 single source) — extraction frames land with
 * job_kind = doc-extract and job_ref = document id, written by the pipeline,
 * so the door answers per-run rows for that anchor. An unknown document
 * refuses with the honest not-found wording before the ledger is touched, and
 * a blank limit passes through so the door applies its own hot default
 * window. Membership guarding stays on the admin bridge.
 */
class DocumentExtractionRunsEndpointTest {

    private DocumentExtractionService documentExtractionService;
    private DocumentExtractionInternalController controller;

    @BeforeEach
    void setUp() {
        documentExtractionService = mock(DocumentExtractionService.class);
        controller = new DocumentExtractionInternalController(documentExtractionService);
    }

    @Test
    void delegatesRunHistoryToSharedRunsDoor() {
        List<TaskEventRunRow> runs = List.of(new TaskEventRunRow(
                "tok-7", 12, true,
                "2026-11-23T10:00:00", "2026-11-23T10:06:00"));
        when(documentExtractionService.extractionRuns(8001L, 20)).thenReturn(runs);

        Response<List<TaskEventRunRow>> response = controller.extractionRuns(8001L, 20);

        assertEquals(runs, response.getData());
    }

    @Test
    void unknownDocumentRefusesWithNotFound() {
        when(documentExtractionService.extractionRuns(99L, null))
                .thenThrow(new ServiceException("文档不存在（或已删除）", BizErrorCode.NOT_FOUND));

        ServiceException refusal = assertThrows(ServiceException.class,
                () -> controller.extractionRuns(99L, null));

        assertEquals(BizErrorCode.NOT_FOUND, refusal.getCode().intValue());
    }

    @Test
    void blankLimitPassesThroughToDoorDefault() {
        when(documentExtractionService.extractionRuns(8002L, null)).thenReturn(List.of());

        Response<List<TaskEventRunRow>> response = controller.extractionRuns(8002L, null);

        assertEquals(List.of(), response.getData());
    }
}
