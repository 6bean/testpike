package com.sixbean.testpike.admin.suites.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.PublicEndpointRegistry;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.suites.api.dto.ScheduleFireView;
import com.sixbean.testpike.admin.suites.infrastructure.remote.client.CoreScheduleClient;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;


class ScheduleFireBridgeTest {

    private final CoreScheduleClient coreScheduleClient = mock(CoreScheduleClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final ScheduleController controller = new ScheduleController(coreScheduleClient, guard);

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    private CurrentUser login() {
        CurrentUser user = new CurrentUser(7L, "u", "名", "部", List.of("TEST_LEAD"), 0L, null);
        UserContext.set(user);
        return user;
    }

    private ScheduleFireView fire(long id) {
        return new ScheduleFireView(id, "suite", 11L, 3L,
                "2026-09-25 02:30:00", "success", "已触发（补偿触发，延迟 3 分钟）", "2026-09-25 02:30:01");
    }

    
    @Test
    void memberSeesFiresWithFiltersPassedThrough() {
        CurrentUser user = login();
        PageView<ScheduleFireView> page = new PageView<>(List.of(fire(1L)), 1L);
        when(coreScheduleClient.listFires(3L, "suite", "failed", 2, 50, "kind", "desc")).thenReturn(page);

        PageView<ScheduleFireView> result = controller.listFires(3L, "suite", "failed", 2, 50, "kind", "desc").getData();

        verify(guard).assertCanView(user, 3L);
        verify(coreScheduleClient).listFires(3L, "suite", "failed", 2, 50, "kind", "desc");
        assertEquals(1L, result.total());
        assertEquals("已触发（补偿触发，延迟 3 分钟）",
                result.list().get(0).message(), "人话结果原样透出不截断");
    }

    
    @Test
    void nonMemberRejectedBeforeCoreCall() {
        login();
        doThrow(new ServiceException("您不是该项目成员，无法查看触发记录", BizErrorCode.FORBIDDEN))
                .when(guard).assertCanView(any(CurrentUser.class), anyLong());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.listFires(3L, null, null, 1, 20, null, null));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
        assert (ex.getMessage().contains("触发记录"));
        verifyNoInteractions(coreScheduleClient);
    }

    
    @Test
    void firesLegIsExemptedMemberGate() throws Exception {
        Method m = ScheduleController.class.getMethod("listFires",
                long.class, String.class, String.class, int.class, int.class, String.class, String.class);
        RequirePermission ann = m.getAnnotation(RequirePermission.class);
        assertNull(ann, "留痕读路径=K6 成员/H1 隔离闸，禁挂功能键");
        assertTrue(PublicEndpointRegistry.isPublic("GET", "/api/v1/schedule/fires"),
                "留痕读路径未登记豁免（裸缺口）");
        assertFalse(PublicEndpointRegistry.isPublic("POST", "/api/v1/schedule/fires"),
                "方法敏感：留痕面只 GET");
    }
}
