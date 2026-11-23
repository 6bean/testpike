package com.sixbean.testpike.admin.webelement.api.controller;

import com.sixbean.testpike.admin.auth.app.component.LoggedUserFixture;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.webelement.api.dto.ReferencingCasePage;
import com.sixbean.testpike.admin.webelement.api.dto.ReferencingCaseView;
import com.sixbean.testpike.admin.webelement.infrastructure.remote.client.CoreWebElementClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Referencing-case bridge contract (MCP tail package 1): the read gate is
 * checked against the project the server resolved for the page — never a
 * client-claimed value — and the paging tokens travel straight through to
 * the core door. An unknown element keeps its honest empty page without a
 * guard round trip, so the drawer never sees a spurious permission error.
 */
class WebElementReferencingCasesBridgeTest {

    private final CoreWebElementClient client = mock(CoreWebElementClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final WebElementReferencingCasesController controller =
            new WebElementReferencingCasesController(client, guard);

    @AfterEach
    void tearDown() {
        LoggedUserFixture.logout();
    }

    @Test
    void referencingCases_gatesOnThePagesOwnProject_and_forwardsPaging() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(client.referencingCases(21L, 2, 10)).thenReturn(page(7L, 35));

        Response<ReferencingCasePage> resp = controller.referencingCases(21L, 2, 10);

        assertEquals("0", resp.getCode());
        assertEquals(35, resp.getData().total());
        assertEquals(2, resp.getData().page());
        assertEquals("登录按钮", resp.getData().elementName());
        verify(guard).assertCanView(any(), eq(7L));
        verify(client).referencingCases(eq(21L), eq(2), eq(10));
    }

    @Test
    void unknownElement_answersEmptyPage_withoutGuardRoundTrip() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(client.referencingCases(99L, 1, 10))
                .thenReturn(new ReferencingCasePage(List.of(), 0, 1, 10, null, null));

        Response<ReferencingCasePage> resp = controller.referencingCases(99L, 1, 10);

        assertEquals("0", resp.getCode());
        assertEquals(0, resp.getData().total());
        assertNull(resp.getData().projectId());
        verify(guard, never()).assertCanView(any(), anyLong());
    }

    private static ReferencingCasePage page(long projectId, long total) {
        return new ReferencingCasePage(
                List.of(new ReferencingCaseView(500L, "CASE-500", "下单主流程", 4, 2, "点击登录按钮")),
                total, 2, 10, "登录按钮", projectId);
    }
}
