package com.sixbean.testpike.admin.llmconfig.api.controller;

import com.sixbean.testpike.admin.llmconfig.api.dto.LlmConfigSaveRequest;
import com.sixbean.testpike.admin.llmconfig.api.dto.ProjectLlmConfigSaveBody;
import com.sixbean.testpike.admin.rbac.app.component.PublicEndpointRegistry;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.rbac.PermActionFamily;
import com.sixbean.testpike.common.rbac.PermissionCatalog;
import com.sixbean.testpike.common.rbac.RoleTemplate;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class LlmConfigDomainPermissionWiringTest {

    
    private static final String KEY = "perm:system:llm-config";

    
    private static String permOf(String methodName, Class<?>... params) {
        try {
            Method m = LlmConfigController.class.getMethod(methodName, params);
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            return ann == null ? null : ann.value();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("llmconfig 域签名漂移（测试先于实现核对方法名/参数）：" + e.getMessage(), e);
        }
    }

    @Test
    void llmconfig域系统链路已挂项目链路豁免零漂移() {
        assertEquals(KEY, permOf("list", Integer.class), "系统清单链路=仅 ADMIN（02 §2.11），挂键零漂移");
        assertEquals(KEY, permOf("create", LlmConfigSaveRequest.class), "新增链路同上");
        assertEquals(KEY, permOf("update", long.class, LlmConfigSaveRequest.class), "更新链路同上");
        assertEquals(KEY, permOf("delete", long.class), "删除链路同上");

        assertNull(permOf("getProjectConfig", long.class),
                "项目配置读取=assertCanView 成员链路，挂 MANAGE 键缩只读成员禁挂");
        assertNull(permOf("updateProjectConfig", long.class, ProjectLlmConfigSaveBody.class),
                "项目选用保存=assertCanManage（ADMIN∪负责人），挂键缩负责人禁挂");

        assertTrue(PublicEndpointRegistry.isPublic("GET", "/api/v1/projects/7/llm-config"),
                "项目配置读取豁免链路未登记（裸缺口）");
        assertTrue(PublicEndpointRegistry.isPublic("PUT", "/api/v1/projects/7/llm-config"),
                "项目选用保存豁免链路未登记（裸缺口）");

        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/llm-configs"),
                "挂键链路禁登记豁免（双通道）");
    }

    @Test
    void K9键族反证钉死仅ADMIN命中证据链() {
        PermissionCatalog k9 = PermissionCatalog.fromKey(KEY);
        assertEquals(PermActionFamily.MANAGE, k9.family(), "K9 键族=MANAGE（证据链勿改）");
        assertEquals("K9", k9.functionId(), "K9 键映射功能点勿改");
        for (RoleTemplate t : RoleTemplate.values()) {
            boolean expectHit = t == RoleTemplate.ADMIN;
            assertEquals(expectHit, t.baselineKeys().contains(KEY),
                    t.name() + " 对 K9 键命中集预期=" + expectHit + "（MANAGE 仅 ADMIN 模板）");
        }
    }

    @Test
    void 豁免模板段不得外溢误吞() {
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/projects/abc/llm-config"),
                "{id} 模板禁吞字面段（非数字永不路由到 handler=豁免面歧义盲区）");
        assertFalse(PublicEndpointRegistry.isPublic("DELETE", "/api/v1/projects/7/llm-config"),
                "方法敏感：llm-config 项目面只 GET/PUT，DELETE 不得误豁免");
        assertFalse(PublicEndpointRegistry.isPublic("POST", "/api/v1/projects/7/llm-config"),
                "方法敏感：项目面无 POST 链路");
        assertTrue(PublicEndpointRegistry.isPublic("GET", "/api/v1/reports/calibers"),
                "既有豁免面不受本域接线影响（基线反证）");
    }
}
