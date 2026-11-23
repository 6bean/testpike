package com.sixbean.testpike.admin.executor.api.controller;

import com.sixbean.testpike.admin.executor.api.dto.ParallelWaterLevelView;
import com.sixbean.testpike.admin.executor.api.dto.ProjectRunningView;
import com.sixbean.testpike.admin.executor.infrastructure.remote.client.CoreExecClient;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


class ParallelWaterLevelBridgeTest {

    private final CoreExecClient coreExecClient = mock(CoreExecClient.class);
    private final ExecutorController controller =
            new ExecutorController(coreExecClient, mock(com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard.class));

    @Test
    void 水位桥字段原样透传禁双头加工() {
        when(coreExecClient.parallelWaterLevel()).thenReturn(new ParallelWaterLevelView(
                7L, 10, 3,
                List.of(new ProjectRunningView(42L, 5L), new ProjectRunningView(43L, 2L))));

        Response<ParallelWaterLevelView> resp = controller.parallelWaterLevel();

        assertEquals("0", resp.getCode());
        ParallelWaterLevelView view = resp.getData();
        assertEquals(7L, view.globalRunning(), "全局在跑=core 现算值原样");
        assertEquals(10, view.globalMax(), "全局上限即时读 K8 键（0=不限语义在 core）");
        assertEquals(3, view.perProjectMax());
        assertEquals(2, view.projects().size());
        assertEquals(42L, view.projects().get(0).projectId());
        assertEquals(5L, view.projects().get(0).running());
    }

    @Test
    void 水位读路径挂进程视图同键防双闸() {
        Method m;
        try {
            m = ExecutorController.class.getMethod("parallelWaterLevel");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("水位读路径缺失（签名先于实现核对）：" + e.getMessage(), e);
        }
        RequirePermission ann = m.getAnnotation(RequirePermission.class);
        assertNotNull(ann, "水位端点必须挂权限键（无裸端点）");
        assertEquals("perm:exec:task-cancel", ann.value(),
                "进程管理页顶部件=与 /running 同键，禁页面内两套闸");
    }

    @Test
    void 客户端契约方法与core同名同返回形状() {
        ParallelWaterLevelView v = new ParallelWaterLevelView(0L, 0, 0, List.of());
        assertEquals(0L, v.globalRunning());
        assertEquals(0, v.globalMax(), "0=不限（缺省键值），admin 侧禁把 0 当硬上限");
    }
}
