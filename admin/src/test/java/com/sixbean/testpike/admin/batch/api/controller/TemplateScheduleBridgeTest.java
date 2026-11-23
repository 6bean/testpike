package com.sixbean.testpike.admin.batch.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.batch.api.dto.BatchTemplateView;
import com.sixbean.testpike.admin.batch.api.dto.TemplateScheduleRequest;
import com.sixbean.testpike.admin.batch.infrastructure.remote.client.CoreBatchClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.PublicEndpointRegistry;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;


class TemplateScheduleBridgeTest {

    private final CoreBatchClient coreBatchClient = mock(CoreBatchClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final BatchController controller = new BatchController(coreBatchClient, guard);

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    private CurrentUser login() {
        CurrentUser user = new CurrentUser(7L, "u", "名", "部", List.of("TEST_LEAD"), 0L, null);
        UserContext.set(user);
        return user;
    }

    private BatchTemplateView template(long projectId) {
        return new BatchTemplateView(9L, projectId, "夜间回归模板", List.of(1L, 2L),
                null, 7L, "2026-09-25 10:00:00", null, 0, null);
    }

    private BatchTemplateView scheduled(long projectId) {
        return new BatchTemplateView(9L, projectId, "夜间回归模板", List.of(1L, 2L),
                null, 7L, "2026-09-25 10:00:00", "0 30 2 * * *", 1, 55L);
    }

    
    @Test
    void memberConfiguresScheduleViaCore() {
        CurrentUser user = login();
        TemplateScheduleRequest req = new TemplateScheduleRequest("0 30 2 * * *", 1, 55L);
        when(coreBatchClient.getTemplate(9L)).thenReturn(template(3L));
        when(coreBatchClient.updateTemplateSchedule(9L, req)).thenReturn(scheduled(3L));

        BatchTemplateView result = controller.updateTemplateSchedule(9L, req).getData();

        verify(guard).assertCanView(user, 3L);
        verify(coreBatchClient).updateTemplateSchedule(9L, req);
        assertEquals("0 30 2 * * *", result.cronExpr());
        assertEquals(1, result.enabled());
        assertEquals(55L, result.scheduleEnvId());
    }

    
    @Test
    void coreOwnerRuleRejectionPropagates() {
        login();
        when(coreBatchClient.getTemplate(9L)).thenReturn(template(3L));
        when(coreBatchClient.updateTemplateSchedule(anyLong(), any()))
                .thenThrow(new ServiceException("仅模板创建人或项目负责人可配置定时复用",
                        BizErrorCode.FORBIDDEN));

        ServiceException ex = assertThrows(ServiceException.class, () -> controller.updateTemplateSchedule(
                9L, new TemplateScheduleRequest("0 30 2 * * *", 1, 55L)));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
        assert (ex.getMessage().contains("定时复用"));
    }

    
    @Test
    void nonMemberRejectedBeforeCoreWrite() {
        login();
        when(coreBatchClient.getTemplate(9L)).thenReturn(template(3L));
        doThrowGuard();

        assertThrows(ServiceException.class, () -> controller.updateTemplateSchedule(
                9L, new TemplateScheduleRequest("0 30 2 * * *", 1, 55L)));
        verifyNoInteractionsSchedule();
    }

    private void doThrowGuard() {
        org.mockito.Mockito.doThrow(
                new ServiceException("您不是该项目成员", BizErrorCode.FORBIDDEN))
                .when(guard).assertCanView(any(CurrentUser.class), anyLong());
    }

    private void verifyNoInteractionsSchedule() {
        verify(coreBatchClient, org.mockito.Mockito.never())
                .updateTemplateSchedule(anyLong(), any());
    }

    
    @Test
    void scheduleLegIsExemptedEntityGate() throws Exception {
        Method m = BatchController.class.getMethod("updateTemplateSchedule",
                long.class, TemplateScheduleRequest.class);
        RequirePermission ann = m.getAnnotation(RequirePermission.class);
        assertNull(ann, "定时配置=K6 成员+core 实体级闸，本包不挂键（扩面须另行零漂移裁定）");
        assertTrue(PublicEndpointRegistry.isPublic("PUT", "/api/v1/batches/templates/9/schedule"),
                "定时配置链路未登记豁免（裸缺口）");
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/batches/templates/9/schedule"),
                "方法敏感：定时配置面只 PUT");
        assertFalse(PublicEndpointRegistry.isPublic("PUT", "/api/v1/batches/templates/9/schedule/x"),
                "单段通配禁越段外溢（/schedule 子路径不得误豁免）");
    }
}
