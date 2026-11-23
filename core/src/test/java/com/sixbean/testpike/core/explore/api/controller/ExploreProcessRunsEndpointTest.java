package com.sixbean.testpike.core.explore.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.events.api.dto.TaskEventRunRow;
import com.sixbean.testpike.core.events.domain.component.AiTaskEventTrimSweeper;
import com.sixbean.testpike.core.events.domain.component.TaskEventWindowReader;
import com.sixbean.testpike.core.events.domain.service.AiGenPublisher;
import com.sixbean.testpike.core.explore.api.dto.ExploreSessionView;
import com.sixbean.testpike.core.explore.domain.service.ExploreService;
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
 * Explore tail revisit door (R67): the detail page offers a "process runs"
 * replay list for a session even after the live window has been trimmed away.
 * The run aggregation is delegated whole to the shared run-history door
 * (same shape as the login-state probe-runs reference), an unknown session
 * refuses with the honest not-found wording before the ledger is touched,
 * and a blank limit passes through so the door applies its own hot default.
 * Membership guarding lives on the admin bridge, mirroring the cursor-window
 * endpoint that already sits next to this one.
 */
class ExploreProcessRunsEndpointTest {

    private ExploreInternalController controller;
    private ExploreService exploreService;
    private TaskEventWindowReader windowReader;

    @BeforeEach
    void setUp() {
        exploreService = mock(ExploreService.class);
        windowReader = mock(TaskEventWindowReader.class);
        controller = new ExploreInternalController(exploreService,
                mock(AiGenPublisher.class), windowReader);
    }

    @Test
    void delegatesRunHistoryToSharedRunsDoor() {
        ExploreSessionView view = new ExploreSessionView();
        view.setId(42L);
        view.setProjectId(7L);
        when(exploreService.get(42L)).thenReturn(view);
        List<TaskEventRunRow> runs = List.of(new TaskEventRunRow(
                "run-20261123", 12, false,
                "2026-11-23T10:00:00", "2026-11-23T10:05:00"));
        when(windowReader.runs(anyString(), anyString(), eq(20))).thenReturn(runs);

        Response<List<TaskEventRunRow>> response = controller.processRuns(42L, 20);

        assertEquals(runs, response.getData());
        verify(windowReader).runs(eq(AiTaskEventTrimSweeper.KIND_EXPLORE), eq("42"), eq(20));
    }

    @Test
    void unknownSessionRefusesBeforeReadingLedger() {
        when(exploreService.get(99L))
                .thenThrow(new ServiceException("探索会话不存在", BizErrorCode.NOT_FOUND));

        ServiceException refusal = assertThrows(ServiceException.class,
                () -> controller.processRuns(99L, null));

        assertEquals(BizErrorCode.NOT_FOUND, refusal.getCode().intValue());
        verify(windowReader, never()).runs(any(), any(), any());
    }

    @Test
    void blankLimitPassesThroughToDoorDefault() {
        ExploreSessionView view = new ExploreSessionView();
        view.setId(43L);
        view.setProjectId(null);
        when(exploreService.get(43L)).thenReturn(view);
        when(windowReader.runs(anyString(), anyString(), eq(null))).thenReturn(List.of());

        Response<List<TaskEventRunRow>> response = controller.processRuns(43L, null);

        assertEquals(List.of(), response.getData());
        verify(windowReader).runs(eq(AiTaskEventTrimSweeper.KIND_EXPLORE), eq("43"), eq(null));
    }
}
