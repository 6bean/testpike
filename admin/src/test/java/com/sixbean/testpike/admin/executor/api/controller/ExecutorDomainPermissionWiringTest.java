package com.sixbean.testpike.admin.executor.api.controller;

import com.sixbean.testpike.admin.rbac.app.component.PublicEndpointRegistry;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.rbac.PermActionFamily;
import com.sixbean.testpike.common.rbac.PermissionCatalog;
import com.sixbean.testpike.common.rbac.RoleTemplate;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ExecutorDomainPermissionWiringTest {

    
    private static final String KEY_H4 = "perm:project:executor-pool";

    
    private static final String KEY_K2 = "perm:system:executor-log";

    
    private static final String KEY_J5 = "perm:batch:run-history";

    
    private static String permOf(String methodName, Class<?>... params) {
        try {
            Method m = ExecutorController.class.getMethod(methodName, params);
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            return ann == null ? null : ann.value();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "executor 域签名漂移（测试先于实现核对方法名/参数）：" + e.getMessage(), e);
        }
    }

    @Test
    void executor域四条链路挂VIEW恒放行三条链路豁免() {
        assertEquals(KEY_H4, permOf("listExecutors"),
                "执行器列表=H4 资源池数据面，挂 VIEW 键恒放行承 coverage 手法");
        assertEquals(KEY_K2, permOf("listExecutorTasks", long.class, String.class,
                        int.class, int.class, String.class, String.class),
                "执行器维度任务列表=K2 执行器日志功能点数据面（02 权限列=项目内成员）");
        assertEquals(KEY_K2, permOf("getTaskLog", long.class),
                "任务 console 日志=K2 同一功能点面（H1 经 projectId 校验链路保留）");
        assertEquals(KEY_J5, permOf("listCaseHistory", long.class, long.class,
                        int.class, int.class),
                "用例执行历史=J5 功能点即本端点（02 §2.7 J5 数据源）");

        assertTrue(permOf("listTasks", long.class, int.class, int.class,
                        String.class, String.class) == null,
                "项目试运行任务列表（F-8）=成员读路径，无贴语义 VIEW 键可借禁挂");
        assertTrue(permOf("getTask", long.class) == null,
                "试运行任务详情（F-8）=同读路径面禁挂");
        assertTrue(permOf("listScreenshots", long.class) == null,
                "截图预览（F-8/B3）=同读路径面禁挂");

        String[][] exempt = {
                {"GET", "/api/v1/executors/tasks"},
                {"GET", "/api/v1/executors/tasks/7"},
                {"GET", "/api/v1/executors/tasks/7/screenshots"},
        };
        for (String[] ep : exempt) {
            assertTrue(PublicEndpointRegistry.isPublic(ep[0], ep[1]),
                    "executor 域豁免链路未登记豁免（裸缺口）：" + ep[0] + " " + ep[1]);
        }

        String[][] wired = {
                {"GET", "/api/v1/executors"},
                {"GET", "/api/v1/executors/7/tasks"},
                {"GET", "/api/v1/executors/tasks/7/logs"},
                {"GET", "/api/v1/executors/cases/7/history"},
        };
        for (String[] ep : wired) {
            assertFalse(PublicEndpointRegistry.isPublic(ep[0], ep[1]),
                    "executor 挂键链路禁登记豁免（双通道）：" + ep[0] + " " + ep[1]);
        }
    }

    @Test
    void 三VIEW键族反证钉死四模板恒命中证据链() {
        String[] keys = {KEY_H4, KEY_K2, KEY_J5};
        String[] functions = {"H4", "K2", "J5"};
        for (int i = 0; i < keys.length; i++) {
            PermissionCatalog c = PermissionCatalog.fromKey(keys[i]);
            assertEquals(PermActionFamily.VIEW, c.family(), keys[i] + " 键族=VIEW（证据链勿改）");
            assertEquals(functions[i], c.functionId(), keys[i] + " 键映射功能点勿改");
            for (RoleTemplate t : RoleTemplate.values()) {
                assertTrue(t.baselineKeys().contains(keys[i]),
                        t.name() + " 对 " + keys[i] + " 应恒命中（VIEW 族四模板基线均含）");
            }
        }
        assertFalse(RoleTemplate.DEVELOPER.baselineKeys().contains("perm:script:trial-run"),
                "B3=RUN 族 developer 不含=豁免链路挂键必缩只读成员（禁借证据链勿改）");
    }

    @Test
    void 豁免模板段不外溢不误伤相邻面() {
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/executors/tasks/screenshots"),
                "/tasks/{taskId} 模板禁吞字面段（豁免面歧义盲区）");
        assertFalse(PublicEndpointRegistry.isPublic("POST", "/api/v1/executors/tasks"),
                "方法敏感：集合面只 GET（POST 不得误豁免）");
        assertFalse(PublicEndpointRegistry.isPublic("PUT", "/api/v1/executors/7/tasks"),
                "方法敏感：/executors/{id}/tasks 只 GET");
        assertTrue(PublicEndpointRegistry.isPublic("GET", "/api/v1/reports/calibers"),
                "既有豁免面不受本域接线影响（基线反证）");
    }
}
