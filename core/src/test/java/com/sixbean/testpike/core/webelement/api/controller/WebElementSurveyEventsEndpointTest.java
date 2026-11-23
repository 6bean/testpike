package com.sixbean.testpike.core.webelement.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.events.api.dto.TaskEventRow;
import com.sixbean.testpike.core.events.domain.component.AiTaskEventTrimSweeper;
import com.sixbean.testpike.core.events.domain.component.TaskEventWindowReader;
import com.sixbean.testpike.core.events.domain.component.TaskEventWriter;
import com.sixbean.testpike.core.events.domain.service.AiGenPublisher;
import com.sixbean.testpike.core.explore.api.dto.ExploreSessionView;
import com.sixbean.testpike.core.explore.domain.service.ExploreService;
import com.sixbean.testpike.core.webelement.api.dto.SurveyEventsPage;
import com.sixbean.testpike.core.webelement.domain.component.WebElementSurveyDispatch;
import com.sixbean.testpike.core.webelement.domain.component.WebElementEvidenceGrantQuery;
import com.sixbean.testpike.core.webelement.domain.component.StepHealEvidenceQuery;
import com.sixbean.testpike.core.webelement.domain.service.WebElementCensusMigrationService;
import com.sixbean.testpike.core.webelement.domain.service.WebElementCensusScriptMigrationService;
import com.sixbean.testpike.core.webelement.domain.service.WebElementService;
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
 * Survey tail read door (R62 segment c): the ledger page replays the
 * web-element-survey process stream by explore session id, so the drawer
 * reads live progress without a second polling channel. The projectId on the
 * answer is resolved server-side from the session row (same posture as the
 * heal-evidence door — never trusted from the client), an unknown session
 * refuses before the ledger is touched, and the window/cursor semantics are
 * delegated whole to the shared TaskEventWindowReader (no second reader).
 */
class WebElementSurveyEventsEndpointTest {

    private WebElementInternalController controller;
    private ExploreService exploreService;
    private TaskEventWindowReader windowReader;

    @BeforeEach
    void setUp() {
        WebElementService service = mock(WebElementService.class);
        WebElementEvidenceGrantQuery grantQuery = mock(WebElementEvidenceGrantQuery.class);
        StepHealEvidenceQuery healQuery = mock(StepHealEvidenceQuery.class);
        WebElementSurveyDispatch dispatch = mock(WebElementSurveyDispatch.class);
        TaskEventWriter taskEvents = mock(TaskEventWriter.class);
        AiGenPublisher publisher = mock(AiGenPublisher.class);
        WebElementCensusMigrationService censusLedger = mock(WebElementCensusMigrationService.class);
        WebElementCensusScriptMigrationService censusScript = mock(WebElementCensusScriptMigrationService.class);
        exploreService = mock(ExploreService.class);
        windowReader = mock(TaskEventWindowReader.class);
        controller = new WebElementInternalController(service, grantQuery, healQuery, dispatch,
                taskEvents, publisher, censusLedger, censusScript, exploreService, windowReader,
                mock(com.sixbean.testpike.core.webelement.domain.service.WebElementRetentionService.class));
    }

    private static ExploreSessionView session(long sessionId, Long projectId) {
        ExploreSessionView view = new ExploreSessionView();
        view.setId(sessionId);
        view.setProjectId(projectId);
        return view;
    }

    @Test
    void resolvesProjectFromSessionRowAndReplaysSharedWindow() {
        when(exploreService.get(42L)).thenReturn(session(42L, 7L));
        List<TaskEventRow> rows = List.of(
                new TaskEventRow(9L, "step", "naming batch 1/3 adopted 4", "{\"n\":4}", "2026-11-18T16:00:00"));
        when(windowReader.since(anyString(), anyString(), anyString(), eq(5L), eq(50)))
                .thenReturn(rows);

        Response<SurveyEventsPage> response = controller.surveyProcessEvents(42L, 5L, 50);

        assertEquals(7L, response.getData().projectId());
        assertEquals(rows, response.getData().events());
        verify(windowReader).since(eq(AiTaskEventTrimSweeper.KIND_WEB_ELEMENT_SURVEY),
                eq("42"), eq("42"), eq(5L), eq(50));
    }

    @Test
    void unknownSessionRefusesBeforeReadingLedger() {
        when(exploreService.get(99L))
                .thenThrow(new ServiceException("探索会话不存在", BizErrorCode.NOT_FOUND));

        ServiceException refusal = assertThrows(ServiceException.class,
                () -> controller.surveyProcessEvents(99L, 0L, null));

        assertEquals(BizErrorCode.NOT_FOUND, refusal.getCode().intValue());
        verify(windowReader, never()).since(any(), any(), any(), any(Long.class), any());
    }

    @Test
    void blankProjectOnSessionRowPassesThroughForAdminGuard() {
        when(exploreService.get(43L)).thenReturn(session(43L, null));
        when(windowReader.since(anyString(), anyString(), anyString(), eq(0L), any()))
                .thenReturn(List.of());

        Response<SurveyEventsPage> response = controller.surveyProcessEvents(43L, 0L, null);

        assertEquals(null, response.getData().projectId());
        assertEquals(List.of(), response.getData().events());
    }
}
