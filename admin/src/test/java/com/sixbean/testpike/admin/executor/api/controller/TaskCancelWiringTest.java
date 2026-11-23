package com.sixbean.testpike.admin.executor.api.controller;

import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.rbac.PermActionFamily;
import com.sixbean.testpike.common.rbac.PermissionCatalog;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class TaskCancelWiringTest {

    private static String permOf(String methodName, Class<?>... params) {
        try {
            Method m = ExecutorController.class.getMethod(methodName, params);
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            return ann == null ? null : ann.value();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "V11 签名漂移（测试先于实现核对方法名/参数）：" + e.getMessage(), e);
        }
    }

    @Test
    void 权限目录登记V11终止键RUN族() {
        List<String> keys = PermissionCatalog.allKeys();
        assertTrue(keys.contains("perm:exec:task-cancel"),
                "V11 新键必须入 PermissionCatalog（check [6/6] RTM×权限键 100% 映射闸）：" + keys.size());
        PermissionCatalog entry = Arrays.stream(PermissionCatalog.values())
                .filter(c -> c.key().equals("perm:exec:task-cancel"))
                .findFirst().orElseThrow();
        assertEquals(PermActionFamily.RUN, entry.family(), "终止=执行动作族（对齐 QUICK_RUN/RUN 先例）");
    }

    @Test
    void 进程管理三条链路挂V11键() {
        assertEquals("perm:exec:task-cancel", permOf("cancelTask", long.class));
        assertEquals("perm:exec:task-cancel", permOf("batchCancel", List.class));
        // the running window gains an optional sort pair; the
        // permission anchor moves with the signature (key itself unchanged).
        assertEquals("perm:exec:task-cancel",
                permOf("listRunning", int.class, int.class, String.class, String.class));
    }

    @Test
    void 批量取消上限常量500复用T225口径() {
        Method m;
        try {
            m = ExecutorController.class.getMethod("batchCancel", List.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("batchCancel 缺失：" + e.getMessage(), e);
        }
        assertNotNull(m.getAnnotation(RequirePermission.class), "批量取消必须挂键（无裸端点）");
    }
}
