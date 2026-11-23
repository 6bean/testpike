package com.sixbean.testpike.admin.executor.api.controller;

import com.sixbean.testpike.admin.executor.api.dto.PoolProjectView;
import com.sixbean.testpike.admin.executor.api.dto.PoolView;
import com.sixbean.testpike.admin.executor.infrastructure.remote.client.CoreExecClient;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


class PoolViewBridgeTest {

    private final CoreExecClient coreExecClient = mock(CoreExecClient.class);
    private final ExecutorController controller = new ExecutorController(coreExecClient,
            mock(com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard.class));

    @Test
    void 池视图桥字段原样透传禁双头加工() {
        when(coreExecClient.listPools()).thenReturn(List.of(
                new PoolView(7L, "web回归池", 3L, 2, 1, 6, 4, 2,
                        List.of(new PoolProjectView(3L, "配额项目", 50, 3, 4, 2))),
                new PoolView(8L, "空池", 3L, 0, 0, 0, 0, 0, List.of())));

        Response<List<PoolView>> resp = controller.listPools();

        assertEquals("0", resp.getCode());
        List<PoolView> pools = resp.getData();
        assertEquals(2, pools.size());
        PoolView first = pools.get(0);
        assertEquals(7L, first.poolId(), "池 id=type=7 标签 id 原样");
        assertEquals("web回归池", first.poolName());
        assertEquals(2, first.memberCount(), "成员数=core 现算值原样");
        assertEquals(1, first.onlineCount());
        assertEquals(6, first.capacitySum());
        assertEquals(4, first.runningTaskCount());
        assertEquals(2, first.pendingTaskCount(), "排队数=core 现算，admin 禁再加工");
        PoolProjectView row = first.projects().get(0);
        assertEquals(3L, row.projectId());
        assertEquals(50, row.quotaPct());
        assertEquals(3, row.slotCap(), "槽位与 claim 闸同源在 core 单点算");
        assertEquals(4, row.runningInPool());
        assertEquals(2, row.pendingCount());
        assertEquals(0, pools.get(1).pendingTaskCount());
        assertEquals(0, pools.get(1).projects().size(), "空池明细空数组出行不吞行");
    }

    @Test
    void 池视图读路径挂H4语义键防双闸() {
        Method m;
        try {
            m = ExecutorController.class.getMethod("listPools");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("池视图读路径缺失（签名先于实现核对）：" + e.getMessage(), e);
        }
        RequirePermission ann = m.getAnnotation(RequirePermission.class);
        assertNotNull(ann, "池视图端点必须挂权限键（无裸端点）");
        assertEquals("perm:project:executor-pool", ann.value(),
                "H4 功能点键与执行器列表同键，禁同一功能点两套闸");
    }

    @Test
    void 客户端契约方法与core同名同返回形状() {
        PoolView v = new PoolView(0L, "", 0L, 0, 0, 0, 0, 0, List.of());
        assertEquals(0L, v.poolId());
        PoolProjectView r = new PoolProjectView(0L, "", null, -1, 0, 0);
        assertEquals(-1, r.slotCap(), "-1=不限槽位（未设配额），语义在 core 单点");
    }
}
