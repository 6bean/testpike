package com.sixbean.testpike.core.batch.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.events.api.dto.TaskEventRunRow;
import com.sixbean.testpike.core.events.domain.component.AiTaskEventTrimSweeper;
import com.sixbean.testpike.core.events.domain.component.TaskEventWindowReader;
import com.sixbean.testpike.core.batch.domain.service.BatchInsightService;
import com.sixbean.testpike.core.batch.domain.service.BatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Batch tail revisit door (R67): the detail page answers an empty cursor
 * window with the run-history aggregation so "frames aged out of retention"
 * stays distinguishable from "this run produced nothing". The aggregation is
 * delegated whole to the shared run-history door (same shape as the explore
 * session reference, R40 single source) — batch frames land with
 * job_kind = batch-exec and job_ref = run_token = batch id, written by the
 * finalize support, so the door answers per-run rows for that anchor. An
 * unknown batch refuses with the honest not-found wording before the ledger
 * is touched, and a blank limit passes through so the door applies its own
 * hot default window. Membership guarding stays on the admin bridge.
 */
class BatchProcessRunsEndpointTest {

    private BatchInternalController controller;
    private BatchService batchService;
    private TaskEventWindowReader windowReader;

    @BeforeEach
    void setUp() {
        batchService = mock(BatchService.class);
        windowReader = mock(TaskEventWindowReader.class);
        controller = new BatchInternalController(batchService,
                mock(BatchInsightService.class), windowReader);
    }

    @Test
    void delegatesRunHistoryToSharedRunsDoor() {
        List<TaskEventRunRow> runs = List.of(new TaskEventRunRow(
                "517", 9, true,
                "2026-11-23T10:00:00", "2026-11-23T10:04:00"));
        when(windowReader.runs(anyString(), anyString(), eq(20))).thenReturn(runs);

        Response<List<TaskEventRunRow>> response = controller.processRuns(517L, 20);

        assertEquals(runs, response.getData());
        verify(windowReader).runs(
                eq(AiTaskEventTrimSweeper.KIND_BATCH_EXEC), eq("517"), eq(20));
    }

    @Test
    void unknownBatchRefusesBeforeReadingLedger() {
        when(batchService.get(99L))
                .thenThrow(new ServiceException("批次不存在", BizErrorCode.NOT_FOUND));

        ServiceException refusal = assertThrows(ServiceException.class,
                () -> controller.processRuns(99L, null));

        assertEquals(BizErrorCode.NOT_FOUND, refusal.getCode().intValue());
        verify(windowReader, never()).runs(any(), any(), any());
    }

    @Test
    void blankLimitPassesThroughToDoorDefault() {
        when(windowReader.runs(anyString(), anyString(), eq(null)))
                .thenReturn(List.of());

        Response<List<TaskEventRunRow>> response = controller.processRuns(518L, null);

        assertEquals(List.of(), response.getData());
        verify(windowReader).runs(
                eq(AiTaskEventTrimSweeper.KIND_BATCH_EXEC), eq("518"), eq(null));
    }
}
