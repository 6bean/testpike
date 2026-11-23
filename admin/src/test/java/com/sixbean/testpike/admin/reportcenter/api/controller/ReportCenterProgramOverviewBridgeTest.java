package com.sixbean.testpike.admin.reportcenter.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.project.infrastructure.remote.client.StubCoreProjectClient;
import com.sixbean.testpike.admin.reportcenter.api.dto.ReportCenterCaliberFootnoteView;
import com.sixbean.testpike.admin.reportcenter.api.dto.ReportCenterDefectBurnWindowView;
import com.sixbean.testpike.admin.reportcenter.api.dto.ReportCenterJourneyTrafficLightView;
import com.sixbean.testpike.admin.reportcenter.api.dto.ReportCenterPerfOverviewView;
import com.sixbean.testpike.admin.reportcenter.api.dto.ReportCenterProgramOverviewView;
import com.sixbean.testpike.admin.reportcenter.api.dto.ReportCenterProjectPassRateView;
import com.sixbean.testpike.admin.reportcenter.app.service.impl.ReportCenterAdminServiceImpl;
import com.sixbean.testpike.admin.reportcenter.infrastructure.remote.client.CoreReportCenterClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract of the program-overview admin bridge (mirror of the core feed).
 *
 * <p>Pinned behaviour: the operator rides the core call as X-Operator-Id while the
 * bridge itself adds no second permission decision (core narrows the feed to lens
 * projects intersected with the caller's visibility and answers an empty feed to
 * outsiders — a duplicate guard here would drift from that single source); the
 * window parameters are forwarded verbatim and the response body passes through
 * untouched so per-project pass-rate readings and registry footnotes cannot be
 * reshaped on the way.
 *
 * <p>CoreReportCenterClient is the integration boundary double for this L2 segment;
 * the live admin-to-core chain is covered by the L3 window (verification levels
 * must not be skipped).
 */
class ReportCenterProgramOverviewBridgeTest {

    private final CoreReportCenterClient client = mock(CoreReportCenterClient.class);
    private final ReportCenterController controller = new ReportCenterController(
            new ReportCenterAdminServiceImpl(client, new ProjectAccessGuard(new StubCoreProjectClient())));

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static CurrentUser login(long userId) {
        CurrentUser user = new CurrentUser(userId, "u" + userId, "U", "D", List.of("USER"),
                System.currentTimeMillis() + 3600_000, null);
        UserContext.set(user);
        return user;
    }

    @Test
    void programOverviewForwardsOperatorAndWindowAndPassesBodyThroughUntouched() {
        login(41L);
        ReportCenterProgramOverviewView feed = new ReportCenterProgramOverviewView(7L, "Set A",
                LocalDateTime.parse("2026-11-01T00:00:00"), LocalDateTime.parse("2026-11-08T00:00:00"),
                List.of(new ReportCenterProjectPassRateView(3L, new BigDecimal("0.85")),
                        new ReportCenterProjectPassRateView(4L, null)),
                List.of(new ReportCenterDefectBurnWindowView(3L, 2, 1, 1, 2),
                        new ReportCenterDefectBurnWindowView(4L, 0, 0, 0, 0)),
                List.of(new ReportCenterJourneyTrafficLightView(3L, 1, 2, 1,
                                LocalDateTime.parse("2026-11-05T00:00:00"), "red"),
                        new ReportCenterJourneyTrafficLightView(4L, 0, 0, 0, null, "grey")),
                List.of(new ReportCenterPerfOverviewView(3L, 2, 1, 88L,
                                LocalDateTime.parse("2026-11-05T00:00:00"), "red",
                                new BigDecimal("40"), new BigDecimal("300"), new BigDecimal("0.5")),
                        new ReportCenterPerfOverviewView(4L, 1, 0, null, null, "grey",
                                null, null, null)),
                List.of(new ReportCenterCaliberFootnoteView("pass_rate", "Pass rate", "v1")));
        when(client.programOverview(41L, 7L, "2026-11-01T00:00:00", null)).thenReturn(feed);

        Response<ReportCenterProgramOverviewView> resp =
                controller.programOverview(7L, "2026-11-01T00:00:00", null);

        assertEquals("0", resp.getCode());
        assertSame(feed, resp.getData(), "bridge must not reshape the core feed");
        verify(client).programOverview(41L, 7L, "2026-11-01T00:00:00", null);
    }

    @Test
    void programOverviewDefaultsAbsentWindowToNullForCoreSideSevenDayFallback() {
        login(41L);
        ReportCenterProgramOverviewView feed = new ReportCenterProgramOverviewView(9L, "Set B",
                LocalDateTime.parse("2026-11-01T02:50:00"), LocalDateTime.parse("2026-11-08T02:50:00"),
                List.of(), List.of(), List.of(), List.of(), List.of());
        when(client.programOverview(41L, 9L, null, null)).thenReturn(feed);

        assertSame(feed, controller.programOverview(9L, null, null).getData());
        verify(client).programOverview(41L, 9L, null, null);
    }
}
