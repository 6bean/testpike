package com.sixbean.testpike.admin.tags.api.controller;

import com.sixbean.testpike.admin.rbac.app.component.PublicEndpointRegistry;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.tags.api.dto.CreateTagRequest;
import com.sixbean.testpike.admin.tags.api.dto.MergeTagRequest;
import com.sixbean.testpike.admin.tags.api.dto.RenameTagRequest;
import com.sixbean.testpike.common.rbac.PermActionFamily;
import com.sixbean.testpike.common.rbac.PermissionCatalog;
import com.sixbean.testpike.common.rbac.RoleTemplate;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class TagDomainPermissionWiringTest {

    
    private static final String KEY = "perm:detail:case-tag";

    
    private static String permOf(String methodName, Class<?>... params) {
        try {
            Method m = TagController.class.getMethod(methodName, params);
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            return ann == null ? null : ann.value();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "tags 域签名漂移（测试先于实现核对方法名/参数）：" + e.getMessage(), e);
        }
    }

    @Test
    void tags域六条链路全挂M3键恒放行零漂移() {
        assertEquals(KEY, permOf("list", long.class, String.class, String.class),
                "标签列表=K6 成员/H1 隔离链路，挂 M3 键恒放行承 coverage 手法");
        assertEquals(KEY, permOf("stats", long.class),
                "标签覆盖率统计=TC-M3-01 同一功能点面");
        assertEquals(KEY, permOf("create", CreateTagRequest.class),
                "新增标签=assertCanWriteCase（成员且非开发），挂恒命中键零漂移、guard 保留");
        assertEquals(KEY, permOf("rename", long.class, RenameTagRequest.class),
                "标签改名=同写路径面");
        assertEquals(KEY, permOf("merge", MergeTagRequest.class),
                "标签合并=同写路径面（IT-M3-02 替代路径）");
        assertEquals(KEY, permOf("delete", long.class),
                "删除标签=同写路径面（被引用 409 在 core 单点）");
        assertEquals(KEY, permOf("listVisible", String.class),
                "聚合可见域标签列表=同 M3 功能点面（多选聚合浏览，逐 id 守卫保留）");
        assertEquals(KEY, permOf("statsVisible", String.class),
                "聚合可见域标签统计=同 TC-M3-01 统计功能点面");

        String[][] wired = {
                {"GET", "/api/v1/tags"},
                {"GET", "/api/v1/tags/stats"},
                {"GET", "/api/v1/tags/visible"},
                {"GET", "/api/v1/tags/stats/visible"},
                {"POST", "/api/v1/tags"},
                {"PUT", "/api/v1/tags/7"},
                {"POST", "/api/v1/tags/merge"},
                {"DELETE", "/api/v1/tags/7"},
        };
        for (String[] ep : wired) {
            assertFalse(PublicEndpointRegistry.isPublic(ep[0], ep[1]),
                    "tags 挂键链路禁登记豁免（双通道）：" + ep[0] + " " + ep[1]);
        }
    }

    @Test
    void M3键族反证钉死四模板恒命中证据链() {
        PermissionCatalog m3 = PermissionCatalog.fromKey(KEY);
        assertEquals(PermActionFamily.EDIT, m3.family(), "M3 键族=EDIT（证据链勿改）");
        assertEquals("M3", m3.functionId(), "M3 键映射功能点勿改");
        for (RoleTemplate t : RoleTemplate.values()) {
            assertTrue(t.baselineKeys().contains(KEY),
                    t.name() + " 对 M3 键应恒命中（EDIT 族四模板基线均含）");
        }
    }

    @Test
    void 挂键端点不误伤相邻豁免面() {
        assertFalse(PublicEndpointRegistry.isPublic("DELETE", "/api/v1/tags/merge"),
                "/merge 面只 POST，DELETE 不得误豁免");
        assertFalse(PublicEndpointRegistry.isPublic("POST", "/api/v1/tags/7"),
                "集合面 POST 建标签，/{id} 字面段不得豁免");
        assertTrue(PublicEndpointRegistry.isPublic("GET", "/api/v1/reports/calibers"),
                "既有豁免面不受本域接线影响（基线反证）");
    }
}
