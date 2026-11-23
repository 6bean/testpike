package com.sixbean.testpike.core.webelement.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.webelement.PageWriteRiskView;
import com.sixbean.testpike.core.webelement.api.dto.CaseDraftFromFailureInput;
import com.sixbean.testpike.core.webelement.domain.service.PageWriteFailureDraftService;
import com.sixbean.testpike.core.webelement.domain.service.PageWriteProfileQueryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Case-draft conversion door contract : the internal POST
 * forwards the three booked coordinates (project segment, risk row id, failure
 * index) to the draft converter verbatim and never re-derives risk, bounds
 * or the project anchor — every refusal lives in the service door so the
 * admin bridge and the kernel prompt share exactly one rule set. The wire
 * keys are pinned here too: the admin side posts these literals and a
 * renamed field would otherwise only surface as a runtime 400.
 */
class PageWriteProfileInternalControllerTest {

    private final PageWriteProfileQueryService queryService = mock(PageWriteProfileQueryService.class);
    private final PageWriteFailureDraftService draftService = mock(PageWriteFailureDraftService.class);
    private final PageWriteProfileInternalController controller =
            new PageWriteProfileInternalController(queryService, draftService);

    @Test
    void riskPages_passesTheProjectSliceThroughWithoutReDeriving() {
        when(queryService.riskPagesForProject(7L))
                .thenReturn(java.util.List.of(new PageWriteRiskView("k1", "http://a/checkout")));

        Response<java.util.List<PageWriteRiskView>> resp = controller.riskPages(7L);

        assertEquals("0", resp.getCode());
        assertEquals("http://a/checkout", resp.getData().get(0).pageUrl());
        verify(queryService).riskPagesForProject(7L);
    }

    @Test
    void caseDraft_forwardsBookedCoordinatesWithoutReDeriving() {
        when(draftService.toCaseDraft(7L, 42L, 1)).thenReturn(900L);

        Response<Long> resp = controller.caseDraft(new CaseDraftFromFailureInput(7L, 42, 1));

        assertEquals("0", resp.getCode());
        assertEquals(900L, resp.getData());
        ArgumentCaptor<Long> project = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> profile = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Integer> index = ArgumentCaptor.forClass(Integer.class);
        verify(draftService).toCaseDraft(project.capture(), profile.capture(), index.capture());
        assertEquals(7L, project.getValue());
        assertEquals(42L, profile.getValue());
        assertEquals(1, index.getValue());
    }

    @Test
    void caseDraft_wireKeysBindTheInputRecord() throws Exception {
        CaseDraftFromFailureInput bound = new ObjectMapper().readValue(
                "{\"projectId\":7,\"profileId\":42,\"failureIndex\":1}",
                CaseDraftFromFailureInput.class);

        assertEquals(7L, bound.projectId());
        assertEquals(42L, bound.profileId());
        assertEquals(1, bound.failureIndex());
    }

    @Test
    void caseDraft_missingProjectIsForwardedSoTheServiceRefusesNotTheDoor() {
        when(draftService.toCaseDraft(eq(null), eq(42L), eq(0))).thenThrow(
                new com.sixbean.testpike.common.exception.ServiceException(
                        "refuses naming the entry path",
                        com.sixbean.testpike.common.exception.BizErrorCode.VALIDATION_ERROR));

        org.junit.jupiter.api.Assertions.assertThrows(
                com.sixbean.testpike.common.exception.ServiceException.class,
                () -> controller.caseDraft(new CaseDraftFromFailureInput(null, 42, 0)));
        verify(draftService).toCaseDraft(null, 42L, 0);
    }

    @Test
    void page_door_stillPassesTheSliceQueryThrough() {
        var view = mock(com.sixbean.testpike.common.webelement.PageWriteProfilePageView.class);
        when(queryService.pageByProject(7L, 2, 50, "save")).thenReturn(view);

        Response<com.sixbean.testpike.common.webelement.PageWriteProfilePageView> resp =
                controller.page(7L, 2, 50, "save");

        assertEquals(view, resp.getData());
        verify(queryService).pageByProject(7L, 2, 50, "save");
    }
}
