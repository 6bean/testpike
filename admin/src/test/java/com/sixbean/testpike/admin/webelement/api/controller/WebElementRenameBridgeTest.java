package com.sixbean.testpike.admin.webelement.api.controller;

import com.sixbean.testpike.admin.auth.app.component.LoggedUserFixture;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.webelement.api.dto.RenameWebElementRequest;
import com.sixbean.testpike.admin.webelement.api.dto.WebElementLedgerView;
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
 * Rename bridge contract : the manage gate is checked
 * against the ROW's owning project (read back from core, never from the
 * request body), and only then is the rename forwarded with the caller's
 * revision token — same posture as the anchors and state doors.
 */
class WebElementRenameBridgeTest {

    private final CoreWebElementClient client = mock(CoreWebElementClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final WebElementController controller = new WebElementController(client, guard);

    @AfterEach
    void tearDown() {
        LoggedUserFixture.logout();
    }

    @Test
    void rename_gatesOnTheRowsOwnProject_then_forwardsToken() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(client.get(21L)).thenReturn(row("错名字按钮"));
        when(client.rename(21L, "登录按钮", 3)).thenReturn(row("登录按钮"));

        Response<WebElementLedgerView> resp =
                controller.rename(21L, new RenameWebElementRequest("登录按钮", 3));

        assertEquals("0", resp.getCode());
        assertEquals("登录按钮", resp.getData().name());
        verify(guard).assertCanManage(any(), eq(7L));
        verify(client).rename(eq(21L), eq("登录按钮"), eq(3));
    }

    private static WebElementLedgerView row(String name) {
        return new WebElementLedgerView(21L, 7L, name, "https://shop.example.com/login",
                List.of(), 0, 0.8, 0L, 0L, 3, null, null);
    }
}
