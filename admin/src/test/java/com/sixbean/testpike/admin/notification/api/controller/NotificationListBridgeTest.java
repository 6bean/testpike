package com.sixbean.testpike.admin.notification.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.notification.api.dto.NotificationView;
import com.sixbean.testpike.admin.notification.infrastructure.remote.client.CoreNotificationClient;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Notification list admin-bridge contract test .
 * <p>Pins that the sort pair is forwarded verbatim to core with no admin-side
 * default rewriting, and that the paged window comes back untouched. Bridge
 * L2 isolates the core client (ScheduleFireBridgeTest precedent); the live
 * admin-core chain is proven by the L3 probe script.
 */
class NotificationListBridgeTest {

    private final CoreNotificationClient coreNotificationClient = mock(CoreNotificationClient.class);
    private final NotificationController controller =
            new NotificationController(coreNotificationClient);

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    private void login() {
        UserContext.set(new CurrentUser(7L, "u", "名", "部", List.of("TEST_LEAD"), 0L, null));
    }

    /** Sort pair forwarded as-is; the current user id drives the scoped read. */
    @Test
    void sortPairPassedThroughWithCurrentUserScope() {
        login();
        PageView<NotificationView> page = new PageView<>(List.of(), 0L);
        when(coreNotificationClient.list(eq(7L), isNull(), eq(1), eq(20),
                isNull(), eq("created_at"), eq("asc"))).thenReturn(page);

        controller.list(null, 1, 20, null, "created_at", "asc");

        verify(coreNotificationClient).list(7L, null, 1, 20, null, "created_at", "asc");
    }

    /** No sort given: core keeps the historical newest-first order (null pass-through). */
    @Test
    void absentSortReachesCoreAsNull() {
        login();
        when(coreNotificationClient.list(anyLong(), any(), anyInt(), anyInt(),
                any(), isNull(), isNull())).thenReturn(new PageView<>(List.of(), 0L));

        controller.list(null, 1, 20, null, null, null);

        verify(coreNotificationClient).list(7L, null, 1, 20, null, null, null);
    }

    /** Filters and paging stay intact alongside the sort pair (no admin-side defaults). */
    @Test
    void filtersAndPagingPassThroughUnchanged() {
        login();
        when(coreNotificationClient.list(7L, 0, 2, 50, "AI_GEN_DONE", "title", "desc"))
                .thenReturn(new PageView<>(List.of(), 3L));

        var result = controller.list(0, 2, 50, "AI_GEN_DONE", "title", "desc").getData();

        assertEquals(3L, result.total());
        verify(coreNotificationClient).list(7L, 0, 2, 50, "AI_GEN_DONE", "title", "desc");
    }
}
