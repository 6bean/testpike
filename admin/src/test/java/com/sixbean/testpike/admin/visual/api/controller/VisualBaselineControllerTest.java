package com.sixbean.testpike.admin.visual.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.LoggedUserFixture;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.visual.api.dto.VisualBaselineView;
import com.sixbean.testpike.admin.visual.infrastructure.remote.client.VisualBaselineClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class VisualBaselineControllerTest {

    private final VisualBaselineClient client = mock(VisualBaselineClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final VisualBaselineController controller =
            new VisualBaselineController(client, guard);

    @AfterEach
    void tearDown() {
        LoggedUserFixture.logout();
    }

    private static VisualBaselineView rowFixture(long id, long projectId) {
        return new VisualBaselineView(id, projectId, "case:9:step:1:shot", 20L,
                "testpike/3/exec-artifact/202609/a-screenshot_1.png", null, 1, "待确认",
                0.02, null, "2026-09-27 07:00:00", null);
    }

    @Test
    void 列表读路径_成员闸先过_项目与用例参数透传core() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(client.listByCase(3L, 9L)).thenReturn(List.of(rowFixture(1L, 3L)));
        Response<List<VisualBaselineView>> resp = controller.list(3L, 9L);
        assertEquals("0", resp.getCode());
        assertEquals(1, resp.getData().size());
        ArgumentCaptor<CurrentUser> user = ArgumentCaptor.forClass(CurrentUser.class);
        verify(guard).assertCanView(user.capture(), eq(3L));
        assertEquals(7L, user.getValue().userId(), "守卫必须用当前登录人（禁拿路径参数冒充）");
        verify(client).listByCase(3L, 9L);
    }

    @Test
    void 确认写路径_权限源走行归属反查_非前端传参() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(client.getById(5L)).thenReturn(rowFixture(5L, 42L));
        when(client.confirm(5L)).thenReturn(rowFixture(5L, 42L));
        Response<VisualBaselineView> resp = controller.confirm(5L);
        assertEquals("0", resp.getCode());
        verify(guard).assertCanWriteCase(any(CurrentUser.class), eq(42L));
        verify(client).confirm(5L);
    }

    @Test
    void 拒绝写路径_行归属反查后用例写闸_核心拒绝错误人话透传() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(client.getById(6L)).thenReturn(rowFixture(6L, 43L));
        controller.reject(6L);
        verify(guard).assertCanWriteCase(any(CurrentUser.class), eq(43L));
        verify(client).reject(6L);
    }

    @Test
    void 查看签发_行归属反查后成员读闸() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(client.getById(8L)).thenReturn(rowFixture(8L, 44L));
        when(client.viewUrl(8L)).thenReturn("http://s3/signed?x=1");
        Response<String> resp = controller.viewUrl(8L);
        assertEquals("http://s3/signed?x=1", resp.getData());
        verify(guard).assertCanView(any(CurrentUser.class), eq(44L));
    }

    @Test
    void 视图字段按名镜像core_状态人话标签不静默丢() {
        VisualBaselineView view = rowFixture(1L, 3L);
        assertEquals("待确认", view.statusLabel(), "人话标签源=core 单点，admin 禁二次映射");
        assertEquals(1, view.status());
        assertEquals("case:9:step:1:shot", view.stepKey());
    }
}
