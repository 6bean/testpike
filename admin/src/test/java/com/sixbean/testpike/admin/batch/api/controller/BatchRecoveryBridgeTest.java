package com.sixbean.testpike.admin.batch.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.batch.api.dto.BatchInfoView;
import com.sixbean.testpike.admin.batch.api.dto.BatchRecoveryView;
import com.sixbean.testpike.admin.batch.infrastructure.remote.client.CoreBatchClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.clientobject.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class BatchRecoveryBridgeTest {

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

    
    @Test
    void memberTriggersRecoveryViaCore() {
        CurrentUser user = login();
        when(coreBatchClient.get(7L)).thenReturn(
                new BatchInfoView(7L, 3L, "B7", "回归批", null, "默认环境", 4, "执行中", 1, null,
                        5, null, null, null, null, null, 1, null, 0, null, null, 0, null));
        BatchRecoveryView view = new BatchRecoveryView(7L, 4, "执行中", 2, 1, 1, 3, 2,
                "崩溃恢复完成：回收失联任务 2 个已重新排队");
        when(coreBatchClient.recover(7L)).thenReturn(view);

        Response<BatchRecoveryView> resp = controller.recover(7L);

        verify(guard).assertCanView(user, 3L);
        verify(coreBatchClient).recover(7L);
        assertSame(view, resp.getData());
        assertEquals(2, resp.getData().recoveredCount());
        assertEquals(1, resp.getData().sweepReclaimedCount(),
                "巡检抢收对账字段桥接原样透传（禁吞字段）");
        assertEquals("崩溃恢复完成：回收失联任务 2 个已重新排队", resp.getData().message());
    }

    
    @Test
    void recoverEndpointCarriesConsolePermission() throws Exception {
        Method m = BatchController.class.getMethod("recover", long.class);
        assertNotNull(m);
        PostMapping post = m.getAnnotation(PostMapping.class);
        assertEquals("/{id}/recover", post.value()[0]);
        RequestMapping rm = BatchController.class.getAnnotation(RequestMapping.class);
        assertEquals("/api/v1/batches", rm.value()[0]);
        com.sixbean.testpike.admin.rbac.app.component.RequirePermission perm =
                m.getAnnotation(com.sixbean.testpike.admin.rbac.app.component.RequirePermission.class);
        assertNotNull(perm, "recover 端点必须挂权限键");
        assertEquals("perm:batch:batch-console", perm.value());
    }
}
