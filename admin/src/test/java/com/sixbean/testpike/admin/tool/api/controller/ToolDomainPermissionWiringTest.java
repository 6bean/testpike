package com.sixbean.testpike.admin.tool.api.controller;

import com.sixbean.testpike.admin.rbac.app.component.PublicEndpointRegistry;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.tool.api.dto.ProjectToolBindingRequest;
import com.sixbean.testpike.admin.tool.api.dto.ToolConfirmTicketView;
import com.sixbean.testpike.admin.tool.api.dto.ToolCreateRequest;
import com.sixbean.testpike.admin.tool.api.dto.ToolInvokeRequest;
import com.sixbean.testpike.admin.tool.api.dto.ToolRejectRequest;
import com.sixbean.testpike.admin.tool.api.dto.ToolRollbackRequest;
import com.sixbean.testpike.admin.tool.api.dto.ToolTestRunInput;
import com.sixbean.testpike.admin.tool.api.dto.ToolUpdateRequest;
import com.sixbean.testpike.common.rbac.PermActionFamily;
import com.sixbean.testpike.common.rbac.PermissionCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ToolDomainPermissionWiringTest {

    
    private static final String KEY = "perm:tool:tool-market";

    
    private static String permOf(Class<?> controller, String methodName, Class<?>... params) {
        try {
            Method m = controller.getMethod(methodName, params);
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            return ann == null ? null : ann.value();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("tools 域签名漂移（测试先于实现核对方法名/参数）：" + e.getMessage(), e);
        }
    }

    @Test
    void 纯ADMIN观测执行链路挂U1目录键零漂移() {
        assertEquals(KEY, permOf(ToolController.class, "invocations", long.class, String.class, String.class,
                int.class, int.class), "U4 调用历史（现行纯 ADMIN=U1 同集）");
        assertEquals(KEY, permOf(ToolController.class, "testRun", long.class, ToolTestRunInput.class), "U4 试跑");
        assertEquals(KEY, permOf(ToolController.class, "confirmToken", long.class), "U4 确认票签发");
        assertEquals(KEY, permOf(ToolController.class, "invoke", long.class, ToolInvokeRequest.class), "U4 携票执行");
    }

    @Test
    void 管理写路径致缩禁挂登记豁免保留guard() {
        assertNull(permOf(ToolController.class, "create", ToolCreateRequest.class),
                "新建=管理面（负责人合法，挂 U1 致缩）");
        assertNull(permOf(ToolController.class, "update", long.class, ToolUpdateRequest.class), "编辑=同上");
        assertNull(permOf(ToolController.class, "delete", long.class, int.class), "删除=同上");
        assertNull(permOf(ToolController.class, "submit", long.class, int.class), "提交=同上");
        assertNull(permOf(ToolController.class, "approve", long.class, int.class), "审批通过=同上");
        assertNull(permOf(ToolController.class, "reject", long.class, ToolRejectRequest.class), "审批拒绝=同上");
        assertNull(permOf(ToolController.class, "retire", long.class, int.class), "下架=同上");
        assertNull(permOf(ToolController.class, "rollback", long.class, ToolRollbackRequest.class),
                "回滚=管理面（同上）");
        assertNull(permOf(ToolMarketController.class, "importTool", MultipartFile.class, Long.class),
                "市场导入=复用 assertCanManage（同上）");
        assertNull(permOf(ProjectToolController.class, "list", long.class),
                "启用集读取=负责人角色闸（guard 项目角色文案保留）");
        assertNull(permOf(ProjectToolController.class, "save", long.class, ProjectToolBindingRequest.class),
                "启用集保存=同上");
    }

    @Test
    void 成员级与登录级读路径不挂键且登记豁免() {
        assertNull(permOf(ToolController.class, "list", String.class, Integer.class, Integer.class,
                Long.class, String.class, Long.class, String.class, String.class, int.class, int.class),
                "工具列表=H1 成员级/登录级");
        assertNull(permOf(ToolController.class, "get", long.class), "工具详情=H1 成员级/登录级");
        assertNull(permOf(ToolController.class, "detail", long.class),
                "完整详情=H1 成员级（v2.38，与 /{id} 同权）");
        assertNull(permOf(ToolController.class, "versions", long.class, int.class, int.class),
                "版本历史=H1 成员级（v2.38）");
        assertNull(permOf(ToolController.class, "versionDetail", long.class, int.class),
                "单版快照=H1 成员级（v2.38）");
        assertNull(permOf(ToolController.class, "download", long.class, Integer.class),
                "单工具下载=H1 成员级（v2.38，@Audit tool-download 在 core 单点）");
        assertNull(permOf(ToolMarketController.class, "list", int.class, int.class, String.class, Integer.class,
                String.class, String.class, String.class), "市场列表=02 权限列全部登录");
        assertNull(permOf(ToolMarketController.class, "download", long.class, String.class),
                "市场下载=02 权限列全部登录");

        String[][] memberLevel = {
                {"POST", "/api/v1/tools"},
                {"PUT", "/api/v1/tools/7"},
                {"DELETE", "/api/v1/tools/7"},
                {"POST", "/api/v1/tools/7/submit"},
                {"POST", "/api/v1/tools/7/approve"},
                {"POST", "/api/v1/tools/7/reject"},
                {"POST", "/api/v1/tools/7/retire"},
                {"POST", "/api/v1/tools/7/rollback"},
                {"POST", "/api/v1/tool-market/import"},
                {"GET", "/api/v1/projects/7/tools"},
                {"PUT", "/api/v1/projects/7/tools"},
                {"GET", "/api/v1/tools"},
                {"GET", "/api/v1/tools/7"},
                {"GET", "/api/v1/tools/7/detail"},
                {"GET", "/api/v1/tools/7/versions"},
                {"GET", "/api/v1/tools/7/versions/2"},
                {"GET", "/api/v1/tools/7/download"},
                {"GET", "/api/v1/tool-market"},
                {"GET", "/api/v1/tool-market/7/download"},
        };
        for (String[] ep : memberLevel) {
            assertTrue(PublicEndpointRegistry.isPublic(ep[0], ep[1]),
                    "成员级/登录级端点未登记豁免（裸缺口）：" + ep[0] + " " + ep[1]);
        }
    }

    @Test
    void 挂键端点不得混入豁免面() {
        String[][] keyed = {
                {"GET", "/api/v1/tools/7/invocations"},
                {"POST", "/api/v1/tools/7/test-run"},
                {"POST", "/api/v1/tools/7/confirm-token"},
                {"POST", "/api/v1/tools/7/invoke"},
        };
        for (String[] ep : keyed) {
            assertFalse(PublicEndpointRegistry.isPublic(ep[0], ep[1]),
                    "功能键端点不得登记豁免面：" + ep[0] + " " + ep[1]);
        }
    }

    @Test
    void 注解键必在册且族反证钉死() {
        PermissionCatalog catalog = PermissionCatalog.fromKey(KEY);
        assertNotNull(catalog, "注解键不在目录：" + KEY);
        assertEquals(PermActionFamily.MANAGE, catalog.family(), "本域挂键族漂移：" + KEY);
        assertEquals(PermActionFamily.MANAGE,
                PermissionCatalog.fromKey("perm:tool:tool-doc-manage").family(), "U2 键族=MANAGE（证据链勿改）");
        assertEquals(PermActionFamily.MANAGE,
                PermissionCatalog.fromKey("perm:tool:tool-spi").family(), "U3 键族=MANAGE（证据链勿改）");
        assertDoesNotThrow(() -> PermissionCatalog.fromKey("perm:tool:tool-consume"),
                "U4 键不在册（反证前提）");
        assertFalse(PermissionCatalog.fromKey("perm:tool:tool-consume").family() != PermActionFamily.RUN,
                "U4 键族=RUN（本链禁挂证据链勿改）");
    }
}
