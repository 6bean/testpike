package com.sixbean.testpike.admin.webelement.api.controller;

import com.sixbean.testpike.admin.auth.app.component.LoggedUserFixture;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.webelement.api.dto.SiteProfileInput;
import com.sixbean.testpike.admin.webelement.api.dto.SiteProfileView;
import com.sixbean.testpike.common.webelement.SiteSurveyRunView;
import com.sixbean.testpike.admin.webelement.infrastructure.remote.client.CoreSiteProfileClient;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Site-profile bridge contract (whole-site survey T696b): reads gate on the
 * project the server resolved for the row, writes require the manage role on
 * that same server-side project, and the revision token travels straight
 * through so the drawer keeps the core stale-write refusal untouched.
 */
class SiteProfileBridgeTest {

    private final CoreSiteProfileClient client = mock(CoreSiteProfileClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final SiteProfileController controller = new SiteProfileController(client, guard);

    @AfterEach
    void tearDown() {
        LoggedUserFixture.logout();
    }

    @Test
    void list_gatesOnRequestedProject_andForwardsSlice() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(client.list(7L)).thenReturn(List.of(row(7L)));

        Response<List<SiteProfileView>> resp = controller.list(7L);

        assertEquals("0", resp.getCode());
        assertEquals(1, resp.getData().size());
        verify(guard).assertCanView(any(), eq(7L));
    }

    @Test
    void get_gatesOnTheRowsOwnProject_notAClientClaim() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(client.get(3L)).thenReturn(row(7L));

        Response<SiteProfileView> resp = controller.get(3L);

        assertEquals("登录态A", resp.getData().loginStateName());
        verify(guard).assertCanView(any(), eq(7L));
    }

    @Test
    void create_requiresProjectAndManageGate_thenForwardsPayload() {
        LoggedUserFixture.loginAsTestLead(7L);
        SiteProfileInput input = input(7L);
        when(client.create(input)).thenReturn(row(7L));

        Response<SiteProfileView> resp = controller.create(input);

        assertEquals("商城主站", resp.getData().name());
        verify(guard).assertCanManage(any(), eq(7L));
    }

    @Test
    void createWithoutProject_namesTheHeaderEntry_andNeverCallsCore() {
        LoggedUserFixture.loginAsTestLead(7L);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.create(input(null)));

        assertEquals("尚未选择项目：请在顶栏项目下拉（未选时显示「请选择项目」）选定项目，再点「保存档案」", ex.getMessage());
        verify(client, never()).create(any());
    }

    @Test
    void update_reReadsRowServerSide_andGatesManageOnItsProject() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(client.get(3L)).thenReturn(row(7L));
        SiteProfileInput input = input(7L);
        when(client.update(eq(3L), eq(input))).thenReturn(row(7L));

        Response<SiteProfileView> resp = controller.update(3L, input);

        assertEquals("/pr/", resp.getData().scopePrefix());
        verify(guard).assertCanManage(any(), eq(7L));
    }

    @Test
    void delete_gatesManageOnTheRowsProject_thenDropsProfileRow() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(client.get(3L)).thenReturn(row(7L));

        Response<Void> resp = controller.delete(3L);

        assertEquals("0", resp.getCode());
        verify(guard).assertCanManage(any(), eq(7L));
        verify(client).delete(3L);
    }

    @Test
    void runs_gatesOnRequestedProject_andForwardsTheCappedSlice() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(client.listRuns(7L, 50)).thenReturn(List.of(runRow(3L)));

        Response<List<SiteSurveyRunView>> resp = controller.listRuns(7L, 50);

        assertEquals("0", resp.getCode());
        assertEquals(1, resp.getData().size());
        assertEquals("running", resp.getData().get(0).status());
        assertEquals(41, resp.getData().get(0).ledgerUpserted());
        verify(guard).assertCanView(any(), eq(7L));
    }

    @Test
    void surveyDispatch_reReadsTheRow_andGatesManageOnTheServerSideProject() {
        LoggedUserFixture.loginAsTestLead(7L);
        // The row lives in another project than the browser segment: the gate
        // and the core hop must both ride the re-read project (9), never 7.
        when(client.get(3L)).thenReturn(row(9L));
        when(client.dispatchSurvey(9L, 3L)).thenReturn(77L);

        Response<Long> resp = controller.dispatchSurvey(3L);

        assertEquals(77L, resp.getData());
        verify(guard).assertCanManage(any(), eq(9L));
        verify(client).dispatchSurvey(9L, 3L);
        verify(client, never()).dispatchSurvey(eq(7L), anyLong());
    }

    private static SiteSurveyRunView runRow(long profileId) {
        return new SiteSurveyRunView(77L, profileId, "商城主站", "https://xx.com/pr",
                "running", 12, 9, 3, 41, 2, 8801L, List.of("登录墙：/pr/login"),
                LocalDateTime.now(), null);
    }

    private static SiteProfileView row(long projectId) {
        return new SiteProfileView(3L, projectId, "商城主站", "https://xx.com/pr",
                "/pr/", List.of("/pr/logout*"), List.of("page"), true,
                Map.of("maxPages", 100), "登录态A", true, 2, LocalDateTime.now());
    }

    private static SiteProfileInput input(Long projectId) {
        return new SiteProfileInput(projectId, "商城主站", "https://xx.com/pr",
                List.of("/pr/logout*"), List.of("page"), true,
                Map.of("maxPages", 100), "登录态A", true, 2);
    }
}
