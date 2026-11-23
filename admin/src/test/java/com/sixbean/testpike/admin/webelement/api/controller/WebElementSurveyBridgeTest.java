package com.sixbean.testpike.admin.webelement.api.controller;

import com.sixbean.testpike.admin.auth.app.component.LoggedUserFixture;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.webelement.api.dto.SurveyBridgeResult;
import com.sixbean.testpike.admin.webelement.api.dto.TriggerSurveyRequest;
import com.sixbean.testpike.admin.webelement.infrastructure.remote.client.CoreWebElementClient;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Admin survey bridge contract (WE-S4 segment P4): triggering a page survey is
 * a write — the manage gate on the body's own project must pass BEFORE the
 * core door is reached, and a missing project segment (no lens selection)
 * refuses with the shared invalid-argument posture instead of firing a
 * projectless dispatch.
 */
class WebElementSurveyBridgeTest {

    private final CoreWebElementClient client = mock(CoreWebElementClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final WebElementController controller = new WebElementController(client, guard);

    @AfterEach
    void tearDown() {
        LoggedUserFixture.logout();
    }

    @Test
    void survey_manage_gate_passes_first_then_forwarded_to_core() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(client.survey(42L, "https://shop.example.com/orders"))
                .thenReturn(new SurveyBridgeResult(555L, "https://shop.example.com/orders"));

        Response<SurveyBridgeResult> resp = controller.survey(
                new TriggerSurveyRequest(42L, "https://shop.example.com/orders"));

        assertEquals("0", resp.getCode());
        assertEquals(555L, resp.getData().sessionId().longValue());
        verify(guard).assertCanManage(any(), eq(42L));
        verify(client).survey(eq(42L), eq("https://shop.example.com/orders"));
    }

    @Test
    void survey_missing_project_slice_refuses_without_touching_core() {
        LoggedUserFixture.loginAsTestLead(7L);
        assertThrows(ServiceException.class,
                () -> controller.survey(new TriggerSurveyRequest(null, "https://shop.example.com/orders")));
        verify(client, never()).survey(anyLong(), anyString());
    }
}
