package com.sixbean.testpike.admin.cases.api.controller;

import com.sixbean.testpike.admin.cases.api.dto.AssignCaseRequest;
import com.sixbean.testpike.admin.cases.api.dto.AutoAssignRequest;
import com.sixbean.testpike.admin.cases.api.dto.BatchAssignRequest;
import com.sixbean.testpike.admin.cases.api.dto.BatchEditRequest;
import com.sixbean.testpike.admin.cases.api.dto.BatchReviewRequest;
import com.sixbean.testpike.admin.cases.api.dto.CloneCaseRequest;
import com.sixbean.testpike.admin.cases.api.dto.CreateCaseRequest;
import com.sixbean.testpike.admin.cases.api.dto.ExportCasesRequest;
import com.sixbean.testpike.admin.cases.api.dto.PreviewFlowchartRequest;
import com.sixbean.testpike.admin.cases.api.dto.QuickExecuteRequest;
import com.sixbean.testpike.admin.cases.api.dto.ReviewCaseRequest;
import com.sixbean.testpike.admin.cases.api.dto.RestoreCaseRequest;
import com.sixbean.testpike.admin.cases.api.dto.RollbackCaseRequest;
import com.sixbean.testpike.admin.cases.api.dto.SaveScriptRequest;
import com.sixbean.testpike.admin.cases.api.dto.SetExcellentRequest;
import com.sixbean.testpike.admin.cases.api.dto.SetFlakyRequest;
import com.sixbean.testpike.admin.cases.api.dto.TrialRunRequest;
import com.sixbean.testpike.admin.cases.api.dto.UpdateCaseRequest;
import com.sixbean.testpike.admin.rbac.app.component.PublicEndpointRegistry;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.tags.api.dto.SetCaseTagsRequest;
import com.sixbean.testpike.common.rbac.PermActionFamily;
import com.sixbean.testpike.common.rbac.PermissionCatalog;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class CaseDomainPermissionWiringTest {

    
    private static String permOf(Class<?> controller, String methodName, Class<?>... params) {
        try {
            Method m = controller.getMethod(methodName, params);
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            return ann == null ? null : ann.value();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("cases 域签名漂移（测试先于实现核对方法名/参数）：" + e.getMessage(), e);
        }
    }

    @Test
    void 功能点直接实现端点挂目录键零漂移() {
        assertEquals("perm:scenario:version-history", permOf(CaseController.class, "listVersions", long.class),
                "Q1 版本历史列表");
        assertEquals("perm:scenario:version-history",
                permOf(CaseController.class, "diffVersion", long.class, int.class, Integer.class), "Q1 版本 diff");
        assertEquals("perm:scenario:version-history",
                permOf(CaseController.class, "rollback", long.class, int.class, RollbackCaseRequest.class),
                "Q1 回滚（功能点描述含回滚）");
        assertEquals("perm:ux:review-efficiency",
                permOf(CaseController.class, "listReviewLogs", long.class), "O8 评审历史侧栏");
        assertEquals("perm:case:case-clone",
                permOf(CaseController.class, "clone", long.class, CloneCaseRequest.class), "A8 克隆");
        assertEquals("perm:ux:case-export",
                permOf(CaseController.class, "export", ExportCasesRequest.class), "P2 导出");
        assertEquals("perm:batch:quick-run",
                permOf(CaseController.class, "quickExecute", long.class, QuickExecuteRequest.class), "I9 快速执行");
        assertEquals("perm:scenario:flaky-isolate",
                permOf(CaseController.class, "setFlaky", long.class, SetFlakyRequest.class), "Q3 flaky 隔离");
        assertEquals("perm:feedback:excellent-mark",
                permOf(CaseController.class, "markExcellent", long.class, SetExcellentRequest.class),
                "D3 优秀标记");
        assertEquals("perm:detail:case-tag",
                permOf(CaseController.class, "setCaseTags", long.class, SetCaseTagsRequest.class), "M3 标签设置");
        assertEquals("perm:security:case-batch-edit",
                permOf(CaseController.class, "batchEdit", BatchEditRequest.class), "N4 批量编辑");
        assertEquals("perm:review:auto-assign",
                permOf(CaseController.class, "autoAssign", AutoAssignRequest.class), "C4 自动分配");
        assertEquals("perm:ux:trash-restore",
                permOf(CaseController.class, "recycleBin", long.class, String.class, int.class, int.class),
                "P1 回收站列表");
        assertEquals("perm:ux:trash-restore",
                permOf(CaseController.class, "clearRecycleBin", long.class), "P1 清空回收站");
        assertEquals("perm:ux:trash-restore",
                permOf(CaseController.class, "restore", long.class, RestoreCaseRequest.class), "P1 还原");
        assertEquals("perm:ux:trash-restore",
                permOf(CaseController.class, "purgeCase", long.class), "P1 彻底删除");
        assertEquals("perm:script:script-version", permOf(ScriptController.class, "listScriptVersions", long.class),
                "B5 脚本版本线");
        assertEquals("perm:script:script-version",
                permOf(ScriptController.class, "diffScriptVersion", long.class, int.class, Integer.class),
                "B5 脚本版本 diff");
        assertEquals("perm:script:to-script",
                permOf(ScriptController.class, "regenerate", long.class, boolean.class), "B1 重生成");
        assertEquals("perm:script:script-edit",
                permOf(ScriptController.class, "saveScript", long.class, Integer.class,
                        SaveScriptRequest.class), "B12 手编保存");
        assertEquals("perm:script:trial-run",
                permOf(ScriptController.class, "trialRun", long.class, TrialRunRequest.class), "B3 试运行");
    }

    @Test
    void 挂键会收窄合法用户或角色闸端点保持成员级并登记豁免() {
        assertNull(permOf(CaseController.class, "list", long.class, Integer.class, String.class, Integer.class,
                Integer.class, Long.class, Integer.class, List.class, String.class, String.class,
                int.class, int.class, Boolean.class), "列表=H1 成员级");
        assertNull(permOf(CaseController.class, "get", long.class), "详情=H1 成员级");
        assertNull(permOf(CaseController.class, "checkReferences", long.class),
                "M2 引用提示（plan-lock 键=MANAGE 族收窄成员=漂移）");
        assertNull(permOf(CaseController.class, "create", CreateCaseRequest.class), "手工创建无功能点");
        assertNull(permOf(CaseController.class, "update", long.class, UpdateCaseRequest.class), "手工编辑无功能点");
        assertNull(permOf(CaseController.class, "delete", long.class), "删除=负责人角色闸");
        assertNull(permOf(CaseController.class, "review", long.class, String.class, ReviewCaseRequest.class),
                "评审（tick27 裁决 case:review 不建键）");
        assertNull(permOf(CaseController.class, "batchReview", BatchReviewRequest.class),
                "批量评审（同上，负责人视角=角色闸）");
        assertNull(permOf(CaseController.class, "batchAssign", BatchAssignRequest.class),
                "批量分配（tick27 裁决不建键）");
        assertNull(permOf(CaseController.class, "assign", long.class, AssignCaseRequest.class),
                "分配（tick27 裁决不建键）");
        assertNull(permOf(ScriptController.class, "getScript", long.class),
                "脚本查看（B1 键 CREATE 族收窄合法只读用户=漂移）");
        assertNull(permOf(ScriptController.class, "flowchart", long.class),
                "流程图查看（C1 键同理）");
        assertNull(permOf(ScriptController.class, "flowchartPreview", long.class,
                PreviewFlowchartRequest.class),
                "流程图草稿预览（T196 查看同权，同理不挂键）");

        String[][] memberLevel = {
                {"GET", "/api/v1/cases"},
                {"GET", "/api/v1/cases/7"},
                {"GET", "/api/v1/cases/7/references"},
                {"POST", "/api/v1/cases"},
                {"PUT", "/api/v1/cases/7"},
                {"DELETE", "/api/v1/cases/7"},
                {"POST", "/api/v1/cases/7/review"},
                {"POST", "/api/v1/cases/batch-review"},
                {"POST", "/api/v1/cases/7/review-lock"},
                {"PUT", "/api/v1/cases/7/review-lock/heartbeat"},
                {"DELETE", "/api/v1/cases/7/review-lock"},
                {"POST", "/api/v1/cases/7/assign"},
                {"POST", "/api/v1/cases/batch-assign"},
                {"GET", "/api/v1/cases/7/script"},
                {"GET", "/api/v1/cases/7/flowchart"},
                {"POST", "/api/v1/cases/7/flowchart/preview"},
                {"POST", "/api/v1/cases/7/rerun-hint"},
        };
        for (String[] ep : memberLevel) {
            assertTrue(PublicEndpointRegistry.isPublic(ep[0], ep[1]),
                    "成员级端点未登记豁免（裸缺口）：" + ep[0] + " " + ep[1]);
        }
    }

    @Test
    void 挂键端点不得混入豁免面() {
        String[][] keyed = {
                {"GET", "/api/v1/cases/7/versions"},
                {"GET", "/api/v1/cases/7/versions/3/diff"},
                {"POST", "/api/v1/cases/7/versions/3/rollback"},
                {"GET", "/api/v1/cases/7/review-logs"},
                {"POST", "/api/v1/cases/7/clone"},
                {"POST", "/api/v1/cases/export"},
                {"POST", "/api/v1/cases/7/quick-execute"},
                {"PUT", "/api/v1/cases/7/flaky"},
                {"PUT", "/api/v1/cases/7/excellent"},
                {"PUT", "/api/v1/cases/7/tags"},
                {"POST", "/api/v1/cases/batch-edit"},
                {"POST", "/api/v1/cases/auto-assign"},
                {"GET", "/api/v1/cases/recycle-bin"},
                {"DELETE", "/api/v1/cases/recycle-bin"},
                {"PUT", "/api/v1/cases/7/restore"},
                {"DELETE", "/api/v1/cases/7/purge"},
                {"GET", "/api/v1/cases/7/scripts/versions"},
                {"GET", "/api/v1/cases/7/scripts/versions/3/diff"},
                {"POST", "/api/v1/cases/7/script/regenerate"},
                {"PUT", "/api/v1/cases/7/script"},
                {"POST", "/api/v1/cases/7/trial-run"},
        };
        for (String[] ep : keyed) {
            assertFalse(PublicEndpointRegistry.isPublic(ep[0], ep[1]),
                    "功能键端点不得登记豁免面：" + ep[0] + " " + ep[1]);
        }
    }

    @Test
    void 注解键必在册且族合法() {
        for (String key : new String[]{"perm:scenario:version-history", "perm:ux:review-efficiency",
                "perm:case:case-clone", "perm:ux:case-export", "perm:batch:quick-run",
                "perm:scenario:flaky-isolate", "perm:detail:case-tag", "perm:security:case-batch-edit",
                "perm:ux:trash-restore", "perm:script:script-version", "perm:script:to-script",
 "perm:script:script-edit",
                "perm:script:trial-run", "perm:feedback:excellent-mark"}) {
            PermissionCatalog catalog = PermissionCatalog.fromKey(key);
            assertNotNull(catalog, "注解键不在目录：" + key);
            PermActionFamily fam = catalog.family();
            assertTrue(fam == PermActionFamily.VIEW || fam == PermActionFamily.EDIT
                            || fam == PermActionFamily.CREATE || fam == PermActionFamily.RUN
                            || fam == PermActionFamily.EXPORT,
                    "cases 域挂键族必须 ∈ {VIEW,EDIT,CREATE,RUN,EXPORT}：" + key + " " + fam);
        }
    }
}
