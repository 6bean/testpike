package com.sixbean.testpike.admin.skill.api.controller;

import com.sixbean.testpike.admin.rbac.app.component.PublicEndpointRegistry;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.skill.api.dto.ProjectSkillBindingRequest;
import com.sixbean.testpike.admin.skill.api.dto.SkillCreateRequest;
import com.sixbean.testpike.admin.skill.api.dto.SkillRejectRequest;
import com.sixbean.testpike.admin.skill.api.dto.SkillUpdateRequest;
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


class SkillDomainPermissionWiringTest {

    
    private static final String KEY = "perm:skill:market";

    
    private static String permOf(Class<?> controller, String methodName, Class<?>... params) {
        try {
            Method m = controller.getMethod(methodName, params);
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            return ann == null ? null : ann.value();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("skills 域签名漂移（测试先于实现核对方法名/参数）：" + e.getMessage(), e);
        }
    }

    @Test
    void 登录读路径挂S3目录键恒放行零漂移() {
        assertEquals(KEY, permOf(SkillController.class, "list", String.class, Integer.class, Integer.class,
                Long.class, Long.class, String.class, String.class, int.class, int.class), "S1 列表");
        assertEquals(KEY, permOf(SkillController.class, "get", long.class), "S1 详情");
        assertEquals(KEY, permOf(SkillController.class, "versions", long.class), "S1 版本链");
        assertEquals(KEY, permOf(SkillController.class, "version", long.class, int.class), "S1 版本快照");
        assertEquals(KEY, permOf(SkillMarketController.class, "list", int.class, int.class, String.class,
                Integer.class, Long.class, String.class, String.class), "S3 市场列表");
        assertEquals(KEY, permOf(SkillMarketController.class, "download", long.class, String.class),
                "S3 市场下载");
    }

    @Test
    void 管理写路径与提交链路致缩禁挂登记豁免() {
        assertNull(permOf(SkillController.class, "create", SkillCreateRequest.class),
                "新建=管理面（负责人合法，挂 S1 致缩 developer 负责人）");
        assertNull(permOf(SkillController.class, "update", long.class, SkillUpdateRequest.class), "编辑=同上");
        assertNull(permOf(SkillController.class, "delete", long.class, int.class), "删除=同上");
        assertNull(permOf(SkillController.class, "approve", long.class, int.class), "审批通过=同上");
        assertNull(permOf(SkillController.class, "reject", long.class, SkillRejectRequest.class), "审批拒绝=同上");
        assertNull(permOf(SkillController.class, "retire", long.class, int.class), "下架=同上");
        assertNull(permOf(SkillController.class, "submit", long.class, int.class),
                "提交=仅创建人（键命中集≠创建人集双向漂移）");
        assertNull(permOf(SkillMarketController.class, "importSkill", MultipartFile.class, Long.class),
                "市场导入=复用 assertCanManage（同上）");
        assertNull(permOf(ProjectSkillController.class, "list", long.class),
                "项目绑定集读取=负责人角色闸（guard 项目角色文案保留）");
        assertNull(permOf(ProjectSkillController.class, "save", long.class, ProjectSkillBindingRequest.class),
                "项目绑定集保存=同上");

        String[][] exempt = {
                {"POST", "/api/v1/skills"},
                {"PUT", "/api/v1/skills/7"},
                {"DELETE", "/api/v1/skills/7"},
                {"POST", "/api/v1/skills/7/submit"},
                {"POST", "/api/v1/skills/7/approve"},
                {"POST", "/api/v1/skills/7/reject"},
                {"POST", "/api/v1/skills/7/retire"},
                {"POST", "/api/v1/skill-market/import"},
                {"GET", "/api/v1/projects/7/skills"},
                {"PUT", "/api/v1/projects/7/skills"},
        };
        for (String[] ep : exempt) {
            assertTrue(PublicEndpointRegistry.isPublic(ep[0], ep[1]),
                    "管理写路径/提交链路未登记豁免（裸缺口）：" + ep[0] + " " + ep[1]);
        }
    }

    @Test
    void 回收站两链路复用TOOL_MARKET键零新键() {
        String key = "perm:tool:tool-market";
        assertEquals(key, permOf(SkillController.class, "recycleBin",
                Long.class, String.class, int.class, int.class), "T221 回收站列表");
        assertEquals(key, permOf(SkillController.class, "restore", long.class, int.class),
                "T221 还原");
        assertEquals(PermActionFamily.MANAGE, PermissionCatalog.fromKey(key).family(),
                "TOOL_MARKET 键族=MANAGE（TC-S-08 403 证据链勿改）");
    }

    @Test
    void 挂键端点不得混入豁免面() {
        String[][] keyed = {
                {"GET", "/api/v1/skills"},
                {"GET", "/api/v1/skills/7"},
                {"GET", "/api/v1/skills/7/versions"},
                {"GET", "/api/v1/skills/7/versions/1"},
                {"GET", "/api/v1/skill-market"},
                {"GET", "/api/v1/skill-market/7/download"},
                {"GET", "/api/v1/skills/recycle-bin"},
                {"PUT", "/api/v1/skills/7/restore"},
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
        assertEquals(PermActionFamily.VIEW, catalog.family(), "读路径挂键族漂移（本域手法=挂 VIEW 恒放行）：" + KEY);
        assertEquals(PermActionFamily.CREATE,
                PermissionCatalog.fromKey("perm:skill:manage").family(), "S1 键族=CREATE（证据链勿改）");
        assertDoesNotThrow(() -> PermissionCatalog.fromKey("perm:skill:approve"), "S2 键不在册（反证前提）");
        assertEquals(PermActionFamily.APPROVE,
                PermissionCatalog.fromKey("perm:skill:approve").family(), "S2 键族=APPROVE（证据链勿改）");
        assertEquals(PermActionFamily.RUN,
                PermissionCatalog.fromKey("perm:skill:consume").family(), "S5 键族=RUN（本链禁挂证据链勿改）");
        assertEquals(PermActionFamily.EDIT,
                PermissionCatalog.fromKey("perm:skill:config").family(), "S4 键族=EDIT（证据链勿改）");
    }
}
