package com.sixbean.testpike.admin.suites.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.suites.infrastructure.remote.client.CoreScheduleClient;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cron preview bridge contract .
 * Asserted surface: the admin bridge forwards cron/count verbatim to core and
 * returns the instants as-is — the fire-time arithmetic stays single-sourced in
 * core's ScheduleCronTimes (red line 6: no second scheduler-side computation on
 * the bridge). An invalid cron refused by core surfaces with its human-readable
 * wording untouched.
 */
class ScheduleCronPreviewBridgeTest {

    private final CoreScheduleClient coreScheduleClient = mock(CoreScheduleClient.class);
    private final ScheduleController controller =
            new ScheduleController(coreScheduleClient, mock(com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard.class));

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    private CurrentUser login() {
        CurrentUser user = new CurrentUser(7L, "u", "名", "部", List.of("TEST_LEAD"), 0L, null);
        UserContext.set(user);
        return user;
    }

    @Test
    void previewPassesCronAndCountThroughAndReturnsInstantsAsIs() {
        login();
        Instant a = Instant.parse("2026-10-26T02:30:00Z");
        Instant b = Instant.parse("2026-10-27T02:30:00Z");
        when(coreScheduleClient.previewCron("0 30 2 * * *", 2)).thenReturn(List.of(a, b));

        List<Instant> result = controller.previewCron("0 30 2 * * *", 2).getData();

        verify(coreScheduleClient).previewCron("0 30 2 * * *", 2);
        assertEquals(List.of(a, b), result, "升序触发点原样透出不改写");
    }

    @Test
    void blankCronPreviewsEmptyThroughTheSameDoor() {
        login();
        when(coreScheduleClient.previewCron("", 3)).thenReturn(List.of());

        assertTrue(controller.previewCron("", 3).getData().isEmpty());
    }

    @Test
    void invalidCronSurfacesCoreRefusalWordingUnchanged() {
        login();
        when(coreScheduleClient.previewCron(anyString(), anyInt()))
                .thenThrow(new ServiceException("cron 表达式不合法（须为 6 段格式）: nope",
                        BizErrorCode.VALIDATION_ERROR));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.previewCron("nope", 3));
        assertTrue(ex.getMessage().contains("cron"));
    }
}
