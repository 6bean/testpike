package com.sixbean.testpike.admin.webelement.api.controller;

import com.sixbean.testpike.admin.auth.app.component.LoggedUserFixture;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.webelement.api.dto.RevisionStormRow;
import com.sixbean.testpike.admin.webelement.api.dto.WebElementHealthRow;
import com.sixbean.testpike.admin.webelement.infrastructure.remote.client.CoreWebElementClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Health / revision-storm bridge contract (web-element observation surface):
 * both admin doors gate on the requested project segment with the same
 * membership guard and view-permission posture as the list door, then pass
 * the core reading through untouched — the board must never re-derive badges
 * or storm counts here, the single ladder lives in core (shared logic stays
 * in one place).
 */
class WebElementHealthStormBridgeTest {

    private final CoreWebElementClient client = mock(CoreWebElementClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final WebElementController controller = new WebElementController(client, guard);

    @AfterEach
    void tearDown() {
        LoggedUserFixture.logout();
    }

    @Test
    void health_gatesOnProject_forwardsPageKeyFilter_andPassesReadingsThrough() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(client.health(7L, "login")).thenReturn(List.of(
                new WebElementHealthRow(21L, true, 12, "green", false),
                new WebElementHealthRow(22L, false, 0, "none", false)));

        Response<List<WebElementHealthRow>> resp = controller.health(7L, "login");

        assertEquals("0", resp.getCode());
        assertEquals(2, resp.getData().size());
        assertEquals("green", resp.getData().get(0).badge());
        assertEquals("none", resp.getData().get(1).badge());
        verify(guard).assertCanView(any(), eq(7L));
        verify(client).health(eq(7L), eq("login"));
    }

    @Test
    void storms_gatesOnProject_keepsTheTwoSignalsApart() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(client.storms(7L)).thenReturn(List.of(
                new RevisionStormRow("checkout", List.of(31L, 32L, 33L), List.of(41L))));

        Response<List<RevisionStormRow>> resp = controller.storms(7L);

        assertEquals("0", resp.getCode());
        RevisionStormRow card = resp.getData().get(0);
        assertEquals("checkout", card.pageKey());
        assertEquals(List.of(31L, 32L, 33L), card.staleElementIds());
        assertEquals(List.of(41L), card.aboutToBreakElementIds());
        verify(guard).assertCanView(any(), eq(7L));
        verify(client).storms(eq(7L));
    }
}
