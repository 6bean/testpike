package com.sixbean.testpike.admin.plugin.api.controller;

import com.sixbean.testpike.admin.plugin.api.dto.PluginUpdateInput;
import com.sixbean.testpike.admin.plugin.api.dto.PluginView;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.rbac.app.component.PublicEndpointRegistry;
import com.sixbean.testpike.common.rbac.PermActionFamily;
import com.sixbean.testpike.common.rbac.PermissionCatalog;
import com.sixbean.testpike.common.rbac.RoleTemplate;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class PluginPermissionWiringTest {

    
    private static final String KEY = "perm:project:plugin-framework";

    
    private static String permOf(String methodName, Class<?>... params) {
        try {
            Method m = PluginAdminController.class.getMethod(methodName, params);
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            return ann == null ? null : ann.value();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("plugin 域签名漂移（测试先于实现核对方法名/参数）：" + e.getMessage(), e);
        }
    }

    @Test
    void plugin域三条链路全挂H8键零豁免() {
        assertEquals(KEY, permOf("list", String.class, Boolean.class),
                "清单链路=纯 ADMIN（02 §2.18），挂键零漂移");
        assertEquals(KEY, permOf("register", org.springframework.web.multipart.MultipartFile.class),
                "上传注册链路=纯 ADMIN，挂键零漂移");
        assertEquals(KEY, permOf("setEnabled", long.class,
                com.sixbean.testpike.admin.plugin.api.dto.PluginEnableInput.class),
                "启停链路=纯 ADMIN，挂键零漂移");
    }

    @Test
    void T242三新链路全挂H8键零豁免() throws Exception {
        assertEquals(KEY, permOf("detail", long.class),
                "详情链路=纯 ADMIN，挂键零漂移");
        assertEquals(KEY, permOf("update", long.class, PluginUpdateInput.class),
                "编辑链路=纯 ADMIN，挂键零漂移");
        assertEquals(KEY, permOf("appliedProjects", long.class),
                "装配反查询链路=纯 ADMIN，挂键零漂移");
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/plugins/9"),
                "detail 挂键链路禁入豁免面");
        assertFalse(PublicEndpointRegistry.isPublic("PUT", "/api/v1/plugins/9"),
                "update 挂键链路禁入豁免面");
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/plugins/9/applied-projects"),
                "applied-projects 挂键链路禁入豁免面");
    }

    @Test
    void 挂键三条链路禁入豁免面双通道盲区() {
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/plugins"),
                "list 挂键链路禁入豁免面");
        assertFalse(PublicEndpointRegistry.isPublic("POST", "/api/v1/plugins"),
                "upload 挂键链路禁入豁免面");
        assertFalse(PublicEndpointRegistry.isPublic("PUT", "/api/v1/plugins/7/enable"),
                "enable 挂键链路禁入豁免面");
        assertFalse(PublicEndpointRegistry.isPublic("DELETE", "/api/v1/plugins"),
                "plugins 面无 DELETE 链路");
    }

    @Test
    void H8键族反证钉死仅ADMIN命中证据链() {
        PermissionCatalog h8 = PermissionCatalog.fromKey(KEY);
        assertEquals(PermActionFamily.MANAGE, h8.family(), "H8 键族=MANAGE（证据链勿改）");
        assertEquals("H8", h8.functionId(), "H8 键映射功能点勿改");
        for (RoleTemplate t : RoleTemplate.values()) {
            boolean expectHit = t == RoleTemplate.ADMIN;
            assertEquals(expectHit, t.baselineKeys().contains(KEY),
                    t.name() + " 对 H8 键命中集预期=" + expectHit + "（MANAGE 仅 ADMIN 模板）");
        }
    }

    @Test
    void apply链路零挂键走豁免面_projectId段数字收紧不误吞() throws Exception {
        Method apply = PluginApplyAdminController.class.getMethod("apply",
                long.class, long.class,
                com.sixbean.testpike.admin.plugin.api.dto.PluginApplyInput.class);
        assertNull(apply.getAnnotation(RequirePermission.class),
                "apply 链路=assertCanManage 豁免链路，禁挂键（挂键即缩负责人=漂移，坑65/坑35）");
        assertTrue(PublicEndpointRegistry.isPublic("POST", "/api/v1/projects/3/plugins/9/apply"),
                "PROJECT_PLUGIN_APPLY 豁免面必须命中真实路径");
        assertFalse(PublicEndpointRegistry.isPublic("POST", "/api/v1/projects/abc/plugins/9/apply"),
                "{id} 段=数字模板，禁吞字面段");
        assertTrue(PublicEndpointRegistry.isPublic("GET", "/api/v1/projects/3/plugins"),
                "M5 计划内变更=listApplied 读路径豁免面命中（成员可读）");
    }

    @Test
    void uninstall链路零挂键走豁免面_数字收紧不误吞() throws Exception {
        Method uninstall = PluginApplyAdminController.class.getMethod("uninstall",
                long.class, long.class);
        assertNull(uninstall.getAnnotation(RequirePermission.class),
                "uninstall 链路=assertCanManage 豁免链路，禁挂键（挂键即缩负责人=漂移，坑65/坑35）");
        assertTrue(PublicEndpointRegistry.isPublic("DELETE", "/api/v1/projects/3/plugins/9"),
                "PROJECT_PLUGIN_UNINSTALL 豁免面必须命中真实路径");
        assertFalse(PublicEndpointRegistry.isPublic("DELETE", "/api/v1/projects/abc/plugins/9"),
                "{id} 段=数字模板，禁吞字面段");
        assertFalse(PublicEndpointRegistry.isPublic("PUT", "/api/v1/projects/3/plugins/9"),
                "uninstall 模板方法敏感，禁吞 PUT");
    }

    @Test
    void listApplied读路径零挂键走豁免面_数字收紧不误吞() throws Exception {
        Method listApplied = PluginApplyAdminController.class.getMethod("listApplied", long.class);
        assertNull(listApplied.getAnnotation(RequirePermission.class),
                "listApplied 链路=assertCanView 豁免链路，禁挂键（挂键即缩成员=漂移，坑65/坑35）");
        assertTrue(PublicEndpointRegistry.isPublic("GET", "/api/v1/projects/3/plugins"),
                "PROJECT_PLUGIN_LIST 豁免面必须命中真实路径");
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/projects/abc/plugins"),
                "{id} 段=数字模板，禁吞字面段");
        assertFalse(PublicEndpointRegistry.isPublic("POST", "/api/v1/projects/3/plugins"),
                "list 模板方法敏感，禁吞 POST（apply 链路另有 /apply 子段路径）");
    }
}
