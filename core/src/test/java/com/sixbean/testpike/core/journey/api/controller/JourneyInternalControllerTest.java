package com.sixbean.testpike.core.journey.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.journey.api.dto.JourneyCreateInput;
import com.sixbean.testpike.core.journey.api.dto.JourneyInfo;
import com.sixbean.testpike.core.journey.api.dto.JourneyRunInfo;
import com.sixbean.testpike.core.journey.api.dto.JourneySegmentInfo;
import com.sixbean.testpike.core.journey.api.dto.JourneySegmentInput;
import com.sixbean.testpike.core.journey.domain.service.JourneyRunService;
import com.sixbean.testpike.core.journey.domain.service.JourneyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Journey internal door contract test (same posture as ProgramInternalControllerTest):
 * the controller is a thin wrapper — the plan surface (create/segment/read) and the run
 * surface (start/get/sign-off) are forwarded verbatim to the services, the operator comes
 * only from the X-Operator-Id header, and every validation, expansion transaction and
 * audit write stays inside the services (red line: no second judgement at the api layer).
 */
class JourneyInternalControllerTest {

    private final JourneyService journeyService = mock(JourneyService.class);
    private final JourneyRunService runService = mock(JourneyRunService.class);
    private final JourneyInternalController controller =
            new JourneyInternalController(journeyService, runService);

    @BeforeEach
    void loginOperator() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(OperatorContext.OPERATOR_ID_HEADER, "7");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private JourneyInfo journey(long id) {
        return new JourneyInfo(id, 3L, "release-lane", 7L, 1, List.of());
    }

    @Test
    void create_forwards_whole_input_and_resolved_operator_and_returns_id() {
        JourneyCreateInput input = new JourneyCreateInput(3L, "release-lane", 7L);
        when(journeyService.createJourney(7L, input)).thenReturn(journey(42L));

        Response<Long> got = controller.create(input);

        assertEquals(42L, got.getData().longValue());
        verify(journeyService).createJourney(7L, input);
    }

    @Test
    void addSegment_forwards_path_id_and_whole_input() {
        JourneySegmentInput input =
                new JourneySegmentInput(1, 11L, "asset", 99L, null, "{\"caseIds\":[1]}");
        JourneySegmentInfo segment =
                new JourneySegmentInfo(5L, 1, 11L, "asset", 99L, null, "{\"caseIds\":[1]}");
        when(journeyService.addSegment(42L, input)).thenReturn(segment);

        Response<JourneySegmentInfo> got = controller.addSegment(42L, input);

        assertSame(segment, got.getData(), "segment view must pass through untouched");
        verify(journeyService).addSegment(42L, input);
    }

    @Test
    void getJourney_forwards_id_and_returns_aggregate() {
        JourneyInfo info = journey(42L);
        when(journeyService.getJourney(42L)).thenReturn(info);
        assertSame(info, controller.getJourney(42L).getData());
    }

    @Test
    void startRun_forwards_path_id_and_operator() {
        JourneyRunInfo run = new JourneyRunInfo(8L, 42L, 1, null, null, List.of());
        when(runService.startRun(7L, 42L)).thenReturn(run);

        assertSame(run, controller.startRun(42L).getData());
        verify(runService).startRun(7L, 42L);
    }

    @Test
    void getRun_and_signOff_forward_run_id_and_operator() {
        JourneyRunInfo read = new JourneyRunInfo(8L, 42L, 2, null, null, List.of());
        when(runService.getRun(8L)).thenReturn(read);
        assertSame(read, controller.getRun(8L).getData());

        JourneyRunInfo signed = new JourneyRunInfo(8L, 42L, 2, 7L, null, List.of());
        when(runService.signOff(7L, 8L)).thenReturn(signed);
        assertSame(signed, controller.signOff(8L).getData());
        verify(runService).signOff(anyLong(), anyLong());
    }
}
