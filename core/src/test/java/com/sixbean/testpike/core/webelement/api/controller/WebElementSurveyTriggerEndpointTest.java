package com.sixbean.testpike.core.webelement.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.ExploreRunMessage;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.events.domain.component.TaskEventWriter;
import com.sixbean.testpike.core.events.domain.service.AiGenPublisher;
import com.sixbean.testpike.core.webelement.api.dto.SurveyResultView;
import com.sixbean.testpike.core.webelement.api.dto.SurveyTriggerInput;
import com.sixbean.testpike.core.webelement.domain.component.WebElementSurveyCollector;
import com.sixbean.testpike.core.webelement.domain.component.WebElementSurveyDispatch;
import com.sixbean.testpike.core.webelement.domain.service.WebElementService;
import com.sixbean.testpike.core.events.domain.component.AiTaskEventTrimSweeper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Manual survey door contract (WE-S4 segment P4): the thin controller resolves
 * the operator from X-Operator-Id only, forwards (operator, project, url) to
 * the shared {@link WebElementSurveyDispatch} (no second INSERT path), opens
 * one web-element-survey process-stream event so the tail drawer can replay
 * "who triggered which page survey", and honestly refuses a blank url instead
 * of dispatching a session that could never collect anything.
 */
class WebElementSurveyTriggerEndpointTest {

    private final WebElementService service = mock(WebElementService.class);
    private final com.sixbean.testpike.core.webelement.domain.component.WebElementEvidenceGrantQuery grantQuery =
            mock(com.sixbean.testpike.core.webelement.domain.component.WebElementEvidenceGrantQuery.class);
    private final com.sixbean.testpike.core.webelement.domain.component.StepHealEvidenceQuery healQuery =
            mock(com.sixbean.testpike.core.webelement.domain.component.StepHealEvidenceQuery.class);
    private final WebElementSurveyDispatch dispatch = mock(WebElementSurveyDispatch.class);
    private final TaskEventWriter events = mock(TaskEventWriter.class);
    private final AiGenPublisher aiGenPublisher = mock(AiGenPublisher.class);
    private final com.sixbean.testpike.core.webelement.domain.service.WebElementCensusMigrationService
            ledgerPosting = mock(
                    com.sixbean.testpike.core.webelement.domain.service.WebElementCensusMigrationService.class);
    private final com.sixbean.testpike.core.webelement.domain.service.WebElementCensusScriptMigrationService
            scriptRefPosting = mock(
                    com.sixbean.testpike.core.webelement.domain.service.WebElementCensusScriptMigrationService.class);

    private WebElementInternalController controller;

    @BeforeEach
    void wireControllerAndOperator() {
        controller = new WebElementInternalController(service, grantQuery, healQuery, dispatch,
                events, aiGenPublisher, ledgerPosting, scriptRefPosting,
                mock(com.sixbean.testpike.core.explore.domain.service.ExploreService.class),
                mock(com.sixbean.testpike.core.events.domain.component.TaskEventWindowReader.class),
                mock(com.sixbean.testpike.core.webelement.domain.service.WebElementRetentionService.class));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(OperatorContext.OPERATOR_ID_HEADER, "7");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(dispatch.dispatch(7L, 42L, "https://shop.example.com/orders"))
                .thenReturn(555L);
    }

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void survey_forwards_operator_project_url_and_answers_session_handle() {
        Response<SurveyResultView> resp = controller.survey(
                new SurveyTriggerInput(42L, "https://shop.example.com/orders"));

        assertEquals("0", resp.getCode());
        assertEquals(555L, resp.getData().sessionId().longValue());
        assertEquals("https://shop.example.com/orders", resp.getData().startUrl());
        verify(dispatch).dispatch(7L, 42L, "https://shop.example.com/orders");
        // The created session must be kicked off, not left sitting status=1:
        // same MQ hop the explore dialog door performs after create.
        verify(aiGenPublisher).publishExploreRun(new ExploreRunMessage(555L, 42L, 7L));
    }

    @Test
    void survey_opens_survey_process_event_carrying_session_and_url() {
        controller.survey(new SurveyTriggerInput(42L, "https://shop.example.com/orders"));

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        // R62 segment b contract: one session = one run tail, token = session id.
        verify(events).step(eq(WebElementSurveyTriggerEndpointTest.jobKind()), eq("555"),
                eq("555"), summary.capture(), anyString());
        assertTrue(summary.getValue().contains(WebElementSurveyCollector.SURVEY_GOAL_PREFIX),
                "audit summary must mark the survey intent for the tail drawer");
        assertTrue(summary.getValue().contains("https://shop.example.com/orders"),
                "audit summary must name the surveyed page");
    }

    @Test
    void survey_blank_url_refuses_without_dispatching() {
        assertThrows(ServiceException.class,
                () -> controller.survey(new SurveyTriggerInput(42L, "   ")));
        verify(dispatch, never()).dispatch(anyLong(), anyLong(), anyString());
        verify(events, never()).step(any(), any(), any(), any(), any());
    }

    @Test
    void census_ledger_drafts_door_forwards_project_anchor_and_answers_posting_counts() {
        when(ledgerPosting.postLedgerDrafts(42L))
                .thenReturn(new com.sixbean.testpike.core.webelement.api.dto.WebElementLedgerPosting(
                        7, 3, 2, 2));

        Response<com.sixbean.testpike.core.webelement.api.dto.CensusPostingView> resp =
                controller.postCensusLedgerDrafts(
                        new com.sixbean.testpike.core.webelement.api.dto.CensusPostingInput(42L));

        assertEquals("0", resp.getCode());
        assertEquals(7, resp.getData().entries());
        assertEquals(3, resp.getData().posted());
        assertEquals(2, resp.getData().alreadyPending());
        assertEquals(2, resp.getData().alreadyLedgered());
        verify(ledgerPosting).postLedgerDrafts(42L);
    }

    @Test
    void census_script_ref_drafts_door_forwards_project_anchor_and_answers_posting_counts() {
        when(scriptRefPosting.postScriptRefDrafts(42L))
                .thenReturn(new com.sixbean.testpike.core.webelement.api.dto.WebElementScriptRefPosting(
                        5, 6, 4, 1, 1));

        Response<com.sixbean.testpike.core.webelement.api.dto.CensusPostingView> resp =
                controller.postCensusScriptRefDrafts(
                        new com.sixbean.testpike.core.webelement.api.dto.CensusPostingInput(42L));

        assertEquals("0", resp.getCode());
        assertEquals(5, resp.getData().entries());
        assertEquals(6, resp.getData().scriptsScanned());
        assertEquals(4, resp.getData().posted());
        assertEquals(1, resp.getData().skippedNotLedgered());
        assertEquals(1, resp.getData().alreadyPending());
        verify(scriptRefPosting).postScriptRefDrafts(42L);
    }

    @Test
    void census_doors_refuse_without_a_project_anchor() {
        assertThrows(ServiceException.class,
                () -> controller.postCensusLedgerDrafts(
                        new com.sixbean.testpike.core.webelement.api.dto.CensusPostingInput(null)));
        assertThrows(ServiceException.class,
                () -> controller.postCensusScriptRefDrafts(
                        new com.sixbean.testpike.core.webelement.api.dto.CensusPostingInput(0L)));
        verify(ledgerPosting, never()).postLedgerDrafts(any());
        verify(scriptRefPosting, never()).postScriptRefDrafts(any());
    }

    /**
 * Process-stream kind of the survey door (R62 segment a). The row-audit
 * kind "element-ledger" (the ledger service's JOB_KIND) belongs to the
 * per-element audit stream keyed by element row id — the kickoff frame of
 * a session must never ride on it.
 */
    private static String jobKind() {
        return AiTaskEventTrimSweeper.KIND_WEB_ELEMENT_SURVEY;
    }
}
