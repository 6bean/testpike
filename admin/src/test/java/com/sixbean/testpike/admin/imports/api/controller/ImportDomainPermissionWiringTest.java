package com.sixbean.testpike.admin.imports.api.controller;

import com.sixbean.testpike.admin.rbac.app.component.PublicEndpointRegistry;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ImportDomainPermissionWiringTest {

    
    private static String permOf(String methodName, Class<?>... params) {
        try {
            Method m = ImportController.class.getMethod(methodName, params);
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            return ann == null ? null : ann.value();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "imports 域签名漂移（测试先于实现核对方法名/参数）：" + e.getMessage(), e);
        }
    }

    @Test
    void imports域三条链路纯豁免零挂键() {
        assertNull(permOf("importCases", long.class, Integer.class, MultipartFile.class),
                "用例导入=K6 项目成员且非开发闸，挂 J1 CREATE 键放大非成员 tester=漂移");
        assertNull(permOf("retryFailedCases", long.class, Integer.class, List.class, MultipartFile.class),
                "导入失败重试=同 importCases 权限面，同裁");
        assertNull(permOf("importRequirements", long.class, MultipartFile.class),
                "需求点导入=K6 负责人/ADMIN 且非开发闸，挂键缩负责人或放大 tester 均漂移");

        String[][] exempt = {
                {"POST", "/api/v1/import/cases"},
                {"POST", "/api/v1/import/cases/retry"},
                {"POST", "/api/v1/import/requirements"},
        };
        for (String[] ep : exempt) {
            assertTrue(PublicEndpointRegistry.isPublic(ep[0], ep[1]),
                    "imports 域豁免链路未登记豁免（裸缺口）：" + ep[0] + " " + ep[1]);
        }
    }

    @Test
    void 豁免模板段不外溢不误伤相邻面() {
        assertFalse(PublicEndpointRegistry.isPublic("POST", "/api/v1/import/cases/"),
                "尾斜杠形态不得被整路径模板误豁免（Spring 路由与豁免面口径一致）");
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/import/cases"),
                "方法敏感：/import/cases 只 POST（GET 模板下载在 /import/templates/*，不同路径）");
        assertFalse(PublicEndpointRegistry.isPublic("DELETE", "/api/v1/import/requirements"),
                "方法敏感：requirements 只 POST");
        assertTrue(PublicEndpointRegistry.isPublic("GET", "/api/v1/reports/calibers"),
                "既有豁免面不受本域接线影响（基线反证）");
    }
}
