package com.sixbean.testpike.admin.reports.api.controller;

import com.sixbean.testpike.admin.rbac.app.component.PublicEndpointRegistry;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.reportcenter.api.controller.ReportCenterController;
import com.sixbean.testpike.admin.reportcenter.api.dto.ReportCenterGenerateInput;
import com.sixbean.testpike.admin.reports.api.dto.ReportExportRequest;
import com.sixbean.testpike.admin.reports.api.dto.ReportGenerateRequest;
import com.sixbean.testpike.common.rbac.PermActionFamily;
import com.sixbean.testpike.common.rbac.PermissionCatalog;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ReportDomainPermissionWiringTest {

    
    private static String permOf(String methodName, Class<?>... params) {
        return permOf(ReportController.class, methodName, params);
    }

    
    private static String permOfCenter(String methodName, Class<?>... params) {
        return permOf(ReportCenterController.class, methodName, params);
    }

    private static String permOf(Class<?> controller, String methodName, Class<?>... params) {
        try {
            Method m = controller.getMethod(methodName, params);
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            return ann == null ? null : ann.value();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("reports 域签名漂移（测试先于实现核对方法名/参数）：" + e.getMessage(), e);
        }
    }

    @Test
    void 功能点直接实现端点挂目录键零漂移() {
        assertEquals("perm:project:project-report", permOf("teamBoard", long.class), "H6 团队看板");
        assertEquals("perm:project:project-report", permOf("aiQuality", long.class), "H6 AI 质量");
        assertEquals("perm:project:project-report", permOf("coverage", long.class), "H6 覆盖率");
        assertEquals("perm:project:project-report", permOf("executionStat", long.class, Long.class), "H6 执行统计");
        assertEquals("perm:project:project-report", permOf("phaseDist", long.class), "K10 阶段分布");
        assertEquals("perm:project:project-report", permOf("compare", String.class), "H6 跨项目对比");
        assertEquals("perm:batch:report-export", permOf("personalReview", long.class, Long.class), "J3 个人评审进度");
        assertEquals("perm:batch:report-export", permOf("export", ReportExportRequest.class), "J3 报表导出");
        assertEquals("perm:testops:summary-report", permOf("generate", ReportGenerateRequest.class), "L4 生成总结报告");
        assertEquals("perm:ux:report-center", permOfCenter("generate", ReportCenterGenerateInput.class),
                "O10 报表中心补生成");
    }

    @Test
    void 成员可看端点保持成员级并登记豁免() {
        assertNull(permOf("listExportTasks", long.class), "导出任务列表保持成员级");
        assertNull(permOf("download", long.class, long.class), "导出任务下载保持成员级");
        assertNull(permOf("listReports", long.class, String.class, int.class, int.class,
                String.class, String.class), "总结报告列表保持成员级");
        assertNull(permOf("getReport", long.class), "总结报告详情保持成员级");
        assertNull(permOf("exportReport", long.class, String.class), "总结报告导出保持成员级");
        assertTrue(PublicEndpointRegistry.isPublic("GET", "/api/v1/reports/export-tasks"),
                "导出任务列表未登记豁免（裸缺口）");
        assertTrue(PublicEndpointRegistry.isPublic("GET", "/api/v1/reports/export-tasks/9999/download"),
                "导出任务下载未登记豁免（裸缺口）");
        assertTrue(PublicEndpointRegistry.isPublic("GET", "/api/v1/reports"), "总结报告列表未登记豁免（裸缺口）");
        assertTrue(PublicEndpointRegistry.isPublic("GET", "/api/v1/reports/9999"), "总结报告详情未登记豁免（裸缺口）");
        assertTrue(PublicEndpointRegistry.isPublic("GET", "/api/v1/reports/9999/export"),
                "总结报告导出未登记豁免（裸缺口）");
        assertNull(permOfCenter("detail", long.class), "报表中心详情保持成员级");
        assertNull(permOfCenter("export", long.class, String.class, String.class), "报表中心导出保持成员级");
        assertTrue(PublicEndpointRegistry.isPublic("GET", "/api/v1/report-center/9999"),
                "报表中心详情未登记豁免（裸缺口）");
        assertTrue(PublicEndpointRegistry.isPublic("GET", "/api/v1/report-center/9999/export"),
                "报表中心导出未登记豁免（裸缺口）");
    }

    @Test
    void 挂键端点不得混入豁免面() {
        String[][] keyed = {
                {"GET", "/api/v1/reports/team-board"},
                {"GET", "/api/v1/reports/ai-quality"},
                {"GET", "/api/v1/reports/coverage"},
                {"GET", "/api/v1/reports/execution-stat"},
                {"GET", "/api/v1/reports/phase-dist"},
                {"GET", "/api/v1/reports/compare"},
                {"GET", "/api/v1/reports/personal-review"},
                {"POST", "/api/v1/reports/export"},
                {"POST", "/api/v1/reports/generate"},
                {"POST", "/api/v1/report-center/generate"},
        };
        for (String[] ep : keyed) {
            assertFalse(PublicEndpointRegistry.isPublic(ep[0], ep[1]),
                    "功能键端点不得登记豁免面：" + ep[0] + " " + ep[1]);
        }
        assertTrue(PublicEndpointRegistry.isPublic("GET", "/api/v1/report-center"),
                "报表中心列表未登记豁免（裸缺口）");
    }

    @Test
    void 注解键必在册且族合法() {
        PermActionFamily[] expected = {PermActionFamily.VIEW, PermActionFamily.EXPORT,
                PermActionFamily.EXPORT, PermActionFamily.MANAGE};
        String[] keys = {"perm:project:project-report", "perm:batch:report-export",
                "perm:testops:summary-report", "perm:ux:report-center"};
        for (int i = 0; i < keys.length; i++) {
            PermissionCatalog catalog = PermissionCatalog.fromKey(keys[i]);
            assertNotNull(catalog, "注解键不在目录：" + keys[i]);
            assertEquals(expected[i], catalog.family(), "本域挂键族漂移：" + keys[i]);
        }
    }
}
