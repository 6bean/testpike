package com.sixbean.testpike.admin.executor.api.controller;

import com.sixbean.testpike.admin.executor.api.dto.ExecStepSnapshotView;
import com.sixbean.testpike.admin.executor.api.dto.ExecTaskView;
import com.sixbean.testpike.admin.executor.infrastructure.remote.client.CoreExecClient;
import com.sixbean.testpike.admin.rbac.app.component.PublicEndpointRegistry;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

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


class ExecSnapshotBridgeTest {

    private final CoreExecClient coreExecClient = mock(CoreExecClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final ExecutorController controller = new ExecutorController(coreExecClient, guard);

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    @Test
    void 快照读路径判权先取任务再assertCanView() {
        UserContext.set(new CurrentUser(7L, "u", "名", "部", List.of("TEST_LEAD"), 0L, null));
        when(coreExecClient.getTask(7L)).thenReturn(new ExecTaskView(
                7L, 42L, 1L, null, 3, "v2", null, null, null, null, null, null, null, null, null,
                null, null, null, 6, null));
        when(coreExecClient.listStepSnapshots(7L)).thenReturn(List.of(
                new ExecStepSnapshotView(7L, 1, "http.request", false, 128L,
                        "{\"method\":\"POST\"}", "{\"status\":200}", "[]", null)));

        Response<List<ExecStepSnapshotView>> resp = controller.listStepSnapshots(7L);

        assertEquals("0", resp.getCode(), "判权通过=正常返回");
        assertEquals(1, resp.getData().size());
        assertEquals("http.request", resp.getData().get(0).kind());
        assertEquals(Boolean.FALSE, resp.getData().get(0).truncated());
        verify(guard).assertCanView(any(CurrentUser.class), eq(42L));
    }

    @Test
    void 任务不存在404透出且不触达快照查询() {
        when(coreExecClient.getTask(9L)).thenThrow(new ServiceException("执行任务不存在", 40401));

        assertThrows(ServiceException.class, () -> controller.listStepSnapshots(9L));
        verify(coreExecClient, never()).listStepSnapshots(anyLong());
    }

    @Test
    void 快照读路径登记豁免禁裸缺口() {
        assertTrue(PublicEndpointRegistry.isPublic("GET", "/api/v1/executors/tasks/7/step-snapshots"),
                "step-snapshots 读路径必须登记豁免（与 screenshots 同面，禁裸缺口）");
    }
}
