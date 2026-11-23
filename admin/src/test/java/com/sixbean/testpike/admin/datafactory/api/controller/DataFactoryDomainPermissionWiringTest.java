package com.sixbean.testpike.admin.datafactory.api.controller;

import com.sixbean.testpike.admin.datafactory.api.dto.DataFactoryCreateRequest;
import com.sixbean.testpike.admin.datafactory.api.dto.DataFactoryUpdateRequest;
import com.sixbean.testpike.admin.rbac.app.component.PublicEndpointRegistry;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.rbac.PermActionFamily;
import com.sixbean.testpike.common.rbac.PermissionCatalog;
import com.sixbean.testpike.common.rbac.RoleTemplate;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class DataFactoryDomainPermissionWiringTest {

    
    private static String permOf(String methodName, Class<?>... params) {
        try {
            Method m = DataFactoryController.class.getMethod(methodName, params);
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            return ann == null ? null : ann.value();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("datafactory 域签名漂移（测试先于实现核对方法名/参数）：" + e.getMessage(), e);
        }
    }

    @Test
    void datafactory域12端点零注解纯豁免域() {
        assertNull(permOf("list", long.class, String.class, Integer.class, int.class, int.class,
                        String.class, String.class),
                "列表=成员只读路径（挂 RUN 键致缩 developer）");
        assertNull(permOf("create", DataFactoryCreateRequest.class),
                "创建=负责人/ADMIN 角色闸（挂键命中集双向不等）");
        assertNull(permOf("get", long.class), "详情=成员只读路径");
        assertNull(permOf("update", long.class, DataFactoryUpdateRequest.class),
                "编辑=负责人/ADMIN 角色闸");
        assertNull(permOf("delete", long.class), "删除=负责人/ADMIN 角色闸");
        assertNull(permOf("run", long.class),
                "一键执行=02 权限列项目内成员（挂 L5 RUN 键拒 developer=缩只读禁挂）");
        assertNull(permOf("runs", long.class, long.class, int.class, int.class), "运行历史=成员只读路径");
        assertNull(permOf("recentRuns", long.class, long.class, int.class), "最近运行=成员只读路径");
        assertNull(permOf("listPoolItems", long.class, Long.class, String.class, Integer.class,
                int.class, int.class), "数据池列表=成员只读路径（挂 N2 RUN 键同缩禁挂）");
        assertNull(permOf("updatePoolItemStatus", long.class, long.class, int.class),
                "池状态回收=负责人/ADMIN 角色闸");
        assertNull(permOf("deletePoolItem", long.class, long.class),
                "池项删除=负责人/ADMIN 角色闸");
        assertNull(permOf("genkitCatalog"),
                "genkit 只读目录=全局静态注册表投影，零项目维度零权限语义（豁免面）");

        String[][] exempt = {
                {"GET", "/api/v1/data-factories"},
                {"POST", "/api/v1/data-factories"},
                {"GET", "/api/v1/data-factories/7"},
                {"PUT", "/api/v1/data-factories/7"},
                {"DELETE", "/api/v1/data-factories/7"},
                {"POST", "/api/v1/data-factories/7/run"},
                {"GET", "/api/v1/data-factories/7/runs"},
                {"GET", "/api/v1/data-factories/7/recent-runs"},
                {"GET", "/api/v1/data-factories/pool-items"},
                {"PUT", "/api/v1/data-factories/pool-items/7/status"},
                {"DELETE", "/api/v1/data-factories/pool-items/7"},
                {"GET", "/api/v1/data-factories/genkit-catalog"},
        };
        for (String[] ep : exempt) {
            assertTrue(PublicEndpointRegistry.isPublic(ep[0], ep[1]),
                    "datafactory 域豁免链路未登记豁免（裸缺口）：" + ep[0] + " " + ep[1]);
        }
    }

    @Test
    void 豁免模板段不得外溢误吞() {
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/data-factories/abc"),
                "{id} 模板禁吞字面段（非数字永不路由到 handler=豁免面歧义盲区）");
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/data-factories/recycle-bin"),
                "{id} 模板禁吞字面兄弟路径（若放宽 [^/]+ 本断言必红=坑29 回归锚）");
        assertFalse(PublicEndpointRegistry.isPublic("DELETE", "/api/v1/data-factories/pool-items/abc"),
                "pool-items {id} 亦数字语义");
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/data-factories/7/run"),
                "方法敏感：run 链路只 POST，GET 不得误豁免");
        assertFalse(PublicEndpointRegistry.isPublic("DELETE", "/api/v1/data-factories/pool-items"),
                "方法敏感：池项删除带 {id} 段，裸父路径 DELETE 不在豁免面");
        assertFalse(PublicEndpointRegistry.isPublic("POST", "/api/v1/data-factories/7"),
                "方法敏感：/{id} 面无 POST 链路");
        assertFalse(PublicEndpointRegistry.isPublic("DELETE", "/api/v1/data-factories/genkit-catalog"),
                "方法敏感：genkit-catalog 只读链路只有 GET");
    }

    @Test
    void 候选键族反证钉死纯豁免证据链() {
        Set<PermActionFamily> developerFamilies = RoleTemplate.DEVELOPER.families();
        assertFalse(developerFamilies.contains(PermActionFamily.RUN),
                "developer 模板含 RUN 族（反证前提勿改）");

        assertEquals(PermActionFamily.RUN,
                PermissionCatalog.fromKey("perm:testops:data-factory").family(),
                "L5 键族=RUN（证据链勿改）");
        assertEquals(PermActionFamily.RUN,
                PermissionCatalog.fromKey("perm:security:data-pool").family(),
                "N2 键族=RUN（证据链勿改）");
    }
}
