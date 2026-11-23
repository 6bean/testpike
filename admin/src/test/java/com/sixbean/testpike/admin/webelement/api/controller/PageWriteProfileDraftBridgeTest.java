package com.sixbean.testpike.admin.webelement.api.controller;

import com.sixbean.testpike.admin.auth.app.component.LoggedUserFixture;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.webelement.infrastructure.remote.client.CorePageWriteProfileClient;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Draft-conversion bridge contract : the browser
 * asks admin to convert one booked failure into a pending case draft; the
 * bridge gates on membership of the requested project segment and forwards
 * the coordinates verbatim, exactly like the read door beside it. No segment
 * rule is re-derived here — core's converter owns the row-existence and
 * bounds refusals, and the filed card still waits behind the generic human
 * confirmation door (the write red line stays intact).
 */
class PageWriteProfileDraftBridgeTest {

    private final CorePageWriteProfileClient client = mock(CorePageWriteProfileClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final PageWriteProfileController controller = new PageWriteProfileController(client, guard);

    @AfterEach
    void tearDown() {
        LoggedUserFixture.logout();
    }

    @Test
    void caseDraft_gatesOnRequestedProject_andForwardsCoordinates() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(client.caseDraftFromFailure(7L, 42L, 1)).thenReturn(900L);

        Response<Long> resp = controller.caseDraft(7L, 42, 1);

        assertEquals("0", resp.getCode());
        assertEquals(900L, resp.getData());
        verify(guard).assertCanView(any(), eq(7L));
    }

    @Test
    void caseDraft_accessRefusalNeverReachesCore() {
        LoggedUserFixture.loginAsTestLead(7L);
        doThrow(new ServiceException("not a member", BizErrorCode.FORBIDDEN))
                .when(guard).assertCanView(any(), eq(7L));

        assertThrows(ServiceException.class, () -> controller.caseDraft(7L, 42, 1));
        verify(client, never()).caseDraftFromFailure(7L, 42L, 1);
    }

    @Test
    void caseDraft_coreRefusalPassesThroughUnchanged() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(client.caseDraftFromFailure(7L, 42L, 5)).thenThrow(
                new ServiceException("index out of range", BizErrorCode.VALIDATION_ERROR));

        ServiceException seen = assertThrows(ServiceException.class,
                () -> controller.caseDraft(7L, 42, 5));
        assertEquals("index out of range", seen.getMessage());
    }
}
