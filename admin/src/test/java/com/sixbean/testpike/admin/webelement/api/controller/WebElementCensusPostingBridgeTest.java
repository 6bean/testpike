package com.sixbean.testpike.admin.webelement.api.controller;

import com.sixbean.testpike.admin.auth.app.component.LoggedUserFixture;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.webelement.api.dto.CensusPostingBridgeResult;
import com.sixbean.testpike.admin.webelement.api.dto.TriggerCensusPostingRequest;
import com.sixbean.testpike.admin.webelement.infrastructure.remote.client.CoreWebElementClient;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Admin census-posting bridge contract : filing migration
 * cards is scoped to one project segment, so the manage gate on the body's
 * own anchor must pass BEFORE the internal door is reached, and a missing
 * top-bar selection refuses with the shared invalid-argument posture
 * instead of firing a projectless posting. The bridge forwards the
 * idempotency account verbatim and never touches drafts itself — landing
 * still rides the draft card human confirmation door.
 */
class WebElementCensusPostingBridgeTest {

    private final CoreWebElementClient client = mock(CoreWebElementClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final WebElementController controller = new WebElementController(client, guard);

    @AfterEach
    void tearDown() {
        LoggedUserFixture.logout();
    }

    @Test
    void ledger_posting_manage_gate_passes_first_then_forwarded_to_core() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(client.postCensusLedgerDrafts(42L)).thenReturn(
                new CensusPostingBridgeResult(7, 3, 2, 2, 0, 0));

        Response<CensusPostingBridgeResult> resp = controller.postCensusLedgerDrafts(
                new TriggerCensusPostingRequest(42L));

        assertEquals("0", resp.getCode());
        assertEquals(3, resp.getData().posted());
        assertEquals(2, resp.getData().alreadyLedgered());
        verify(guard).assertCanManage(any(), eq(42L));
        verify(client).postCensusLedgerDrafts(eq(42L));
    }

    @Test
    void script_ref_posting_manage_gate_passes_first_then_forwarded_to_core() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(client.postCensusScriptRefDrafts(42L)).thenReturn(
                new CensusPostingBridgeResult(5, 4, 0, 0, 6, 1));

        Response<CensusPostingBridgeResult> resp = controller.postCensusScriptRefDrafts(
                new TriggerCensusPostingRequest(42L));

        assertEquals("0", resp.getCode());
        assertEquals(4, resp.getData().posted());
        assertEquals(6, resp.getData().scriptsScanned());
        assertEquals(1, resp.getData().skippedNotLedgered());
        verify(guard).assertCanManage(any(), eq(42L));
        verify(client).postCensusScriptRefDrafts(eq(42L));
    }

    @Test
    void missing_project_slice_refuses_without_touching_core() {
        LoggedUserFixture.loginAsTestLead(7L);
        ServiceException ledgerRefused = assertThrows(ServiceException.class,
                () -> controller.postCensusLedgerDrafts(new TriggerCensusPostingRequest(null)));
        assertTrue(ledgerRefused.getMessage().contains("顶栏"),
                "refusal must point at the header project selector: " + ledgerRefused.getMessage());
        assertTrue(ledgerRefused.getMessage().contains("页面元素库"),
                "refusal must name the page hosting the trigger: " + ledgerRefused.getMessage());
        assertTrue(ledgerRefused.getMessage().contains("批量迁移入库"),
                "refusal must name the ledger trigger button: " + ledgerRefused.getMessage());
        ServiceException refRefused = assertThrows(ServiceException.class,
                () -> controller.postCensusScriptRefDrafts(new TriggerCensusPostingRequest(null)));
        assertTrue(refRefused.getMessage().contains("批量引用替换"),
                "refusal must name the replacement trigger button: " + refRefused.getMessage());
        verify(client, never()).postCensusLedgerDrafts(anyLong());
        verify(client, never()).postCensusScriptRefDrafts(anyLong());
    }
}
