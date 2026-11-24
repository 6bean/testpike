package com.sixbean.testpike.admin.defects.api.controller;

import com.sixbean.testpike.admin.defects.api.dto.DefectCreateRequest;
import com.sixbean.testpike.admin.defects.api.dto.DefectTransitionRequest;
import com.sixbean.testpike.admin.defects.api.dto.DefectUpdateRequest;
import com.sixbean.testpike.admin.defects.api.dto.ExportDefectsRequest;
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


class DefectLibraryDomainPermissionWiringTest {

    
    private static final String KEY_L2 = "perm:testops:defect-lib";

    
    private static final String KEY_P2 = "perm:ux:case-export";

    
    private static String permOf(String methodName, Class<?>... params) {
        try {
            Method m = DefectLibraryController.class.getMethod(methodName, params);
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            return ann == null ? null : ann.value();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "defect-library 域签名漂移（测试先于实现核对方法名/参数）：" + e.getMessage(), e);
        }
    }

    @Test
    void 缺陷库六条链路挂L2键恒放行导出链路挂P2键() {
        assertEquals(KEY_L2, permOf("list", long.class, Integer.class, Integer.class,
                        Long.class, Long.class, String.class, int.class, int.class,
                        String.class, String.class, long.class),
                "缺陷列表=K6 成员/H1 隔离链路，挂 L2 键恒放行承 coverage 手法");
        assertEquals(KEY_L2, permOf("create", DefectCreateRequest.class),
                "创建缺陷=assertCanWriteCase（成员且非开发），挂恒命中键零漂移、guard 保留");
        assertEquals(KEY_L2, permOf("get", long.class),
                "缺陷详情=K6 成员/H1 隔离链路，同上");
        assertEquals(KEY_L2, permOf("update", long.class, DefectUpdateRequest.class),
                "编辑缺陷=同写路径面（version 乐观锁在 core）");
        assertEquals(KEY_L2, permOf("transition", long.class, DefectTransitionRequest.class),
                "状态流转=修复人/验证人/负责人/ADMIN 实体级闸（IT-L2-02），挂恒命中键"
                        + "拦截器不介入、guard 人话「修复人或报告人」保留（l4-l2-defect 锚）");
        assertEquals(KEY_L2, permOf("summaryByCase", long.class),
                "用例缺陷摘要=TC-L2-02 同一功能点面读路径");

        assertEquals(KEY_P2, permOf("export", ExportDefectsRequest.class),
                "缺陷批量导出=P2 用例/缺陷批量导出功能点，承用例导出挂键先例");

        String[][] wired = {
                {"GET", "/api/v1/defect-library"},
                {"POST", "/api/v1/defect-library"},
                {"GET", "/api/v1/defect-library/7"},
                {"PUT", "/api/v1/defect-library/7"},
                {"POST", "/api/v1/defect-library/7/transition"},
                {"GET", "/api/v1/defect-library/summary"},
                {"POST", "/api/v1/defect-library/export"},
        };
        for (String[] ep : wired) {
            assertFalse(PublicEndpointRegistry.isPublic(ep[0], ep[1]),
                    "defect-library 挂键链路禁登记豁免（双通道）：" + ep[0] + " " + ep[1]);
        }
    }

    @Test
    void L2与P2键族反证钉死证据链() {
        PermissionCatalog l2 = PermissionCatalog.fromKey(KEY_L2);
        assertEquals(PermActionFamily.VIEW, l2.family(), "L2 键族=VIEW（证据链勿改）");
        assertEquals("L2", l2.functionId(), "L2 键映射功能点勿改");
        for (RoleTemplate t : RoleTemplate.values()) {
            assertTrue(t.baselineKeys().contains(KEY_L2),
                    t.name() + " 对 L2 键应恒命中（VIEW 族四模板基线均含）");
        }
        PermissionCatalog p2 = PermissionCatalog.fromKey(KEY_P2);
        assertEquals(PermActionFamily.EXPORT, p2.family(), "P2 键族=EXPORT（证据链勿改）");
        assertFalse(RoleTemplate.DEVELOPER.baselineKeys().contains(KEY_P2),
                "developer 不含 EXPORT 族=挂键拒 developer，与 assertCanExportDefect "
                        + "现行拒开发同向（零漂移前提，勿改）");
    }

    @Test
    void 挂键端点不误伤相邻面() {
        assertFalse(PublicEndpointRegistry.isPublic("POST", "/api/v1/defect-library/summary"),
                "/summary 面只 GET，POST 不得误豁免");
        assertFalse(PublicEndpointRegistry.isPublic("DELETE", "/api/v1/defect-library/7"),
                "契约面无 DELETE /{id}，不得误豁免");
        assertTrue(PublicEndpointRegistry.isPublic("GET", "/api/v1/reports/calibers"),
                "既有豁免面不受本域接线影响（基线反证）");
    }
}
