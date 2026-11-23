package com.sixbean.testpike.admin.batch.api.controller;

import com.sixbean.testpike.admin.batch.api.dto.BatchCreateRequest;
import com.sixbean.testpike.admin.batch.api.dto.BatchFromTemplateRequest;
import com.sixbean.testpike.admin.batch.api.dto.BatchTemplateSaveRequest;
import com.sixbean.testpike.admin.batch.api.dto.BatchUpdateRequest;
import com.sixbean.testpike.admin.rbac.app.component.PublicEndpointRegistry;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.rbac.PermActionFamily;
import com.sixbean.testpike.common.rbac.PermissionCatalog;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class BatchDomainPermissionWiringTest {

    
    private static String permOf(String methodName, Class<?>... params) {
        try {
            Method m = BatchController.class.getMethod(methodName, params);
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            return ann == null ? null : ann.value();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("batches 域签名漂移（测试先于实现核对方法名/参数）：" + e.getMessage(), e);
        }
    }

    @Test
    void 功能点直接实现端点挂目录键零漂移() {
        assertEquals("perm:batch:batch-view",
                permOf("list", long.class, Integer.class, String.class, int.class, int.class,
                        String.class, String.class), "I1 批次列表");
        assertEquals("perm:batch:batch-view", permOf("get", long.class), "I1 批次详情");
        assertEquals("perm:batch:batch-view", permOf("listCases", long.class), "I1 批次明细用例");
        assertEquals("perm:batch:batch-view", permOf("listTemplates", long.class), "I1 模板列表");
        assertEquals("perm:batch:batch-view", permOf("create", BatchCreateRequest.class), "I1 创建草稿批次");
        assertEquals("perm:batch:batch-view", permOf("update", long.class, BatchUpdateRequest.class), "I1 编辑草稿批次");
        assertEquals("perm:batch:batch-view", permOf("start", long.class), "I1 启动批次");
        assertEquals("perm:batch:batch-view",
                permOf("saveAsTemplate", long.class, BatchTemplateSaveRequest.class), "I1 保存为模板");
        assertEquals("perm:batch:batch-view", permOf("createFromTemplate", BatchFromTemplateRequest.class),
                "I1 模板复用建批");
        assertEquals("perm:batch:batch-console", permOf("pause", long.class, Integer.class), "I6 暂停");
        assertEquals("perm:batch:batch-console", permOf("resume", long.class, Integer.class), "I6 续跑");
        assertEquals("perm:batch:batch-console", permOf("cancel", long.class, Integer.class), "I6 取消");
        assertEquals("perm:batch:batch-console", permOf("skipCase", long.class), "I6 跳过当前用例");
        assertEquals("perm:batch:batch-console", permOf("rerunFailed", long.class), "I6 重跑失败集");
    }

    @Test
    void 实体级闸端点保持成员级并登记豁免() {
        assertNull(permOf("deleteTemplate", long.class),
                "删模板=创建人/项目负责人实体级闸（TC-I1-02 管理权限），承 CASES_DELETE 先例不挂键");
        assertTrue(PublicEndpointRegistry.isPublic("DELETE", "/api/v1/batches/templates/9999"),
                "删模板端点未登记豁免（裸缺口）");
    }

    @Test
    void 挂键端点不得混入豁免面() {
        String[][] keyed = {
                {"GET", "/api/v1/batches?projectId=1"},
                {"POST", "/api/v1/batches"},
                {"GET", "/api/v1/batches/7"},
                {"PUT", "/api/v1/batches/7"},
                {"POST", "/api/v1/batches/7/start"},
                {"POST", "/api/v1/batches/7/pause"},
                {"POST", "/api/v1/batches/7/resume"},
                {"POST", "/api/v1/batches/7/cancel"},
                {"POST", "/api/v1/batches/7/skip-case"},
                {"POST", "/api/v1/batches/7/rerun-failed"},
                {"GET", "/api/v1/batches/7/cases"},
                {"POST", "/api/v1/batches/7/save-as-template"},
                {"GET", "/api/v1/batches/templates?projectId=1"},
                {"POST", "/api/v1/batches/from-template"},
        };
        for (String[] ep : keyed) {
            String path = ep[1].contains("?") ? ep[1].substring(0, ep[1].indexOf('?')) : ep[1];
            assertFalse(PublicEndpointRegistry.isPublic(ep[0], path),
                    "功能键端点不得登记豁免面：" + ep[0] + " " + path);
        }
    }

    @Test
    void 注解键必在册且族合法() {
        for (String key : new String[] {"perm:batch:batch-view", "perm:batch:batch-console"}) {
            PermissionCatalog catalog = PermissionCatalog.fromKey(key);
            assertNotNull(catalog, "注解键不在目录：" + key);
            assertEquals(PermActionFamily.VIEW, catalog.family(), "batches 域挂键族必须=VIEW：" + key);
        }
    }
}
