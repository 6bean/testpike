package com.sixbean.testpike.admin.project.api.controller;

import com.sixbean.testpike.admin.project.api.dto.CreateProjectRequest;
import com.sixbean.testpike.admin.project.api.dto.DeployInfoRequest;
import com.sixbean.testpike.admin.project.api.dto.EnvParamsRequest;
import com.sixbean.testpike.admin.project.api.dto.EnvRequest;
import com.sixbean.testpike.admin.project.api.dto.MemberAddRequest;
import com.sixbean.testpike.admin.project.api.dto.MemberRoleRequest;
import com.sixbean.testpike.admin.project.api.dto.UpdateProjectRequest;
import com.sixbean.testpike.admin.rbac.app.component.PublicEndpointRegistry;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.rbac.PermActionFamily;
import com.sixbean.testpike.common.rbac.PermissionCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ProjectDomainPermissionWiringTest {

    private static final String KEY = "perm:project:project-view";

    
    private static String permOf(Class<?> controller, String methodName, Class<?>... params) {
        try {
            Method m = controller.getMethod(methodName, params);
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            return ann == null ? null : ann.value();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("project 域签名漂移（测试先于实现核对方法名/参数）：" + e.getMessage(), e);
        }
    }

    @Test
    void 功能点直接实现端点挂目录键零漂移() {
        assertEquals(KEY, permOf(ProjectController.class, "list",
                int.class, int.class, String.class, Integer.class, String.class, String.class), "H1 项目列表");
        assertEquals(KEY, permOf(ProjectController.class, "get", long.class), "H1 项目详情");
        assertEquals(KEY, permOf(ProjectController.class, "create", CreateProjectRequest.class),
                "H1 创建项目（guard 管理员/负责人闸保留）");
        assertEquals(KEY, permOf(ProjectController.class, "update", long.class, UpdateProjectRequest.class),
                "H1 编辑项目（guard 负责人闸保留）");
        assertEquals(KEY, permOf(ProjectController.class, "archive", long.class),
                "H1 归档项目（guard 仅管理员闸保留）");
        assertEquals(KEY, permOf(ProjectController.class, "archived", int.class, int.class),
                "P1 归档视图（guard 仅管理员闸保留）");
        assertEquals(KEY, permOf(ProjectController.class, "restore", long.class),
                "P1 还原归档（guard 仅管理员闸保留）");
        assertEquals(KEY, permOf(ProjectController.class, "purge", long.class, boolean.class),
                "M4 彻底删除（guard 仅管理员闸保留）");
        assertEquals(KEY, permOf(MemberController.class, "list", long.class), "H2 成员列表");
        assertEquals(KEY, permOf(MemberController.class, "add", long.class, MemberAddRequest.class),
                "H2 添加成员（guard 负责人闸保留）");
        assertEquals(KEY, permOf(MemberController.class, "updateRole", long.class, long.class,
                MemberRoleRequest.class), "H2 改角色（guard 负责人闸保留，l4-t143 文案锚）");
        assertEquals(KEY, permOf(MemberController.class, "remove", long.class, long.class),
                "H2 移除成员（guard 负责人闸保留）");
        assertEquals(KEY, permOf(ProjectEnvController.class, "list", long.class), "H3 环境列表");
        assertEquals(KEY, permOf(ProjectEnvController.class, "create", long.class, EnvRequest.class),
                "H3 创建环境（guard 负责人闸保留）");
        assertEquals(KEY, permOf(ProjectEnvController.class, "update", long.class, long.class,
                EnvRequest.class), "H3 编辑环境（guard 负责人闸保留）");
        assertEquals(KEY, permOf(ProjectEnvController.class, "delete", long.class, long.class,
                boolean.class), "H3 删除环境（guard 负责人闸保留）");
        assertEquals(KEY, permOf(ProjectEnvController.class, "getEnvHealth", long.class, long.class),
                "R1 健康快照（成员可读）");
        assertEquals(KEY, permOf(ProjectEnvController.class, "probeEnvHealth", long.class, long.class),
                "R1 手动探测（现行成员可触发=挂 MANAGE 致缩禁挂，VIEW 零漂移）");
        assertEquals(KEY, permOf(ProjectEnvController.class, "listParams", long.class, long.class),
                "T144 参数打码列表（成员可读）");
        assertEquals(KEY, permOf(ProjectEnvController.class, "saveParams", long.class, long.class,
                EnvParamsRequest.class), "T144 参数保存（guard 负责人闸保留）");
        assertEquals(KEY, permOf(EnvDeployController.class, "recordDeploy", long.class,
                DeployInfoRequest.class), "L6 记录部署（guard 负责人闸保留，l4-l6-deploy 文案锚）");
        assertEquals(KEY, permOf(EnvDeployController.class, "getDeployInfo", long.class),
                "L6 部署查询（成员可看）");
    }

    @Test
    void 全域映射链路无裸缺口全挂注解() {
        for (Class<?> controller : new Class<?>[]{ProjectController.class, MemberController.class,
                ProjectEnvController.class, EnvDeployController.class}) {
            for (Method m : controller.getDeclaredMethods()) {
                boolean mapped = java.util.Arrays.stream(m.getAnnotations())
                        .anyMatch(ProjectDomainPermissionWiringTest::isMapping);
                if (mapped) {
                    assertNotNull(m.getAnnotation(RequirePermission.class),
                            "project 域裸缺口：" + controller.getSimpleName() + "#" + m.getName());
                }
            }
        }
    }

    @Test
    void 挂键端点不得混入豁免面() {
        String[][] keyed = {
                {"GET", "/api/v1/projects"},
                {"GET", "/api/v1/projects/9999"},
                {"POST", "/api/v1/projects"},
                {"PUT", "/api/v1/projects/9999"},
                {"DELETE", "/api/v1/projects/9999"},
                {"GET", "/api/v1/projects/archived"},
                {"PUT", "/api/v1/projects/9999/restore"},
                {"DELETE", "/api/v1/projects/9999/purge"},
                {"GET", "/api/v1/projects/9999/members"},
                {"POST", "/api/v1/projects/9999/members"},
                {"PUT", "/api/v1/projects/9999/members/8888"},
                {"DELETE", "/api/v1/projects/9999/members/8888"},
                {"GET", "/api/v1/projects/9999/envs"},
                {"POST", "/api/v1/projects/9999/envs"},
                {"PUT", "/api/v1/projects/9999/envs/7777"},
                {"DELETE", "/api/v1/projects/9999/envs/7777"},
                {"GET", "/api/v1/projects/9999/envs/7777/health"},
                {"POST", "/api/v1/projects/9999/envs/7777/health"},
                {"GET", "/api/v1/projects/9999/envs/7777/params"},
                {"PUT", "/api/v1/projects/9999/envs/7777/params"},
                {"PUT", "/api/v1/envs/7777/deploy-info"},
                {"GET", "/api/v1/envs/7777/deploy-info"},
        };
        for (String[] ep : keyed) {
            assertFalse(PublicEndpointRegistry.isPublic(ep[0], ep[1]),
                    "功能键端点不得登记豁免面：" + ep[0] + " " + ep[1]);
        }
    }

    @Test
    void 注解键必在册且族合法() {
        PermissionCatalog catalog = PermissionCatalog.fromKey(KEY);
        assertNotNull(catalog, "注解键不在目录：" + KEY);
        assertEquals(PermActionFamily.VIEW, catalog.family(), "本域挂键族漂移：" + KEY);
        assertEquals(PermActionFamily.MANAGE,
                PermissionCatalog.fromKey("perm:project:project-perm").family(),
                "H2 键族=MANAGE（漂移证据链，勿改）");
        assertEquals(PermActionFamily.MANAGE,
                PermissionCatalog.fromKey("perm:project:project-env-config").family(),
                "H3 键族=MANAGE（漂移证据链，勿改）");
    }

    private static boolean isMapping(Annotation a) {
        return a instanceof GetMapping || a instanceof PostMapping || a instanceof PutMapping
                || a instanceof DeleteMapping || a instanceof PatchMapping;
    }
}
