package com.sixbean.testpike.admin.ai.api.controller;

import com.sixbean.testpike.admin.rbac.app.component.PublicEndpointRegistry;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.ai.api.dto.AiBatchGenerateRequest;
import com.sixbean.testpike.admin.ai.api.dto.AiGenerateRequest;
import com.sixbean.testpike.admin.ai.api.dto.ConfirmExtractionRequest;
import com.sixbean.testpike.admin.ai.api.dto.DocumentCategoryRequest;
import com.sixbean.testpike.admin.ai.api.dto.DocumentUsageSwitchesRequest;
import com.sixbean.testpike.admin.ai.api.dto.ExtractRequest;
import com.sixbean.testpike.common.rbac.PermActionFamily;
import com.sixbean.testpike.common.rbac.PermissionCatalog;
import com.sixbean.testpike.common.rbac.RoleTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Current ledger: 18 endpoints (AiController 7 + DocumentController 11, the batch/limit display
 * lane and the extraction-cancel lane included) are <b>annotation-free</b> — only the K6 guard applies — and
 * <p>The method name carries no endpoint count on purpose. Three document endpoints (row-level
 * category edit, usage-switch edit, single-chunk evidence read) were added after this lane was
 * written and the previous count-bearing name hid the drift: every document endpoint must be
 * pinned below, so a new endpoint without an explicit exemption entry fails the build instead of
 * passing a stale ledger silently.
 *
 */
class AiDomainPermissionWiringTest {

    
    private static String permOf(Class<?> controller, String methodName, Class<?>... params) {
        try {
            Method m = controller.getMethod(methodName, params);
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            return ann == null ? null : ann.value();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("ai 域签名漂移（测试先于实现核对方法名/参数）：" + e.getMessage(), e);
        }
    }

    @Test
    void ai域端点零注解纯豁免域() {
        // Assertion 1: 4 generation writes + 7 member reads + 6 requirement-input writes carry no
        // permission key at all (verdicts 1/2/3: every candidate key on this face belongs to the
        // CREATE/RUN/MANAGE families, so mounting any family on any lane would either shrink or
        // widen the allow-set = drift; the developer-template family counter-evidence sits in the
        // lane test below).
        assertNull(permOf(AiController.class, "generate", AiGenerateRequest.class, String.class),
                "发起生成=成员且非开发闸（挂 A1 CREATE 致缩）");
        assertNull(permOf(AiController.class, "getTask", long.class), "任务查询=成员只读路径");
        assertNull(permOf(AiController.class, "retry", long.class), "重试=生成写路径同律");
        // Generation-task stop door (tail drawer): same write law as retry
        // (assertCanWriteCase on the task's own project), no candidate key.
        assertNull(permOf(AiController.class, "cancel", long.class), "停止=生成写路径同律（成员且非开发闸）");
        assertNull(permOf(AiController.class, "batchEstimate", AiBatchGenerateRequest.class),
                "批量预估=生成写路径同律");
        assertNull(permOf(AiController.class, "batchLimit"),
                "批量上限读取=登录闸展示链路（V8/T225 新端点，非项目资源无键可挂）");
        assertNull(permOf(AiController.class, "batchGenerate", AiBatchGenerateRequest.class, String.class),
                "批量生成=挂 A2 CREATE 致缩");
        assertNull(permOf(AiController.class, "getBatch", long.class), "批量进度=成员只读路径");
        // the two stage0 preview lanes join the same exemption family —
        // write door shares batch-estimate's assertCanWriteCase verdict ①, read-back
        // shares the member read verdict ② (both guard-only, no candidate key).
        assertNull(permOf(AiController.class, "runScenarioPreview",
                        com.sixbean.testpike.admin.ai.api.dto.ScenarioPreviewRequest.class),
                "阶段0预览运行=生成写路径同律（assertCanWriteCase）");
        assertNull(permOf(AiController.class, "listScenarioPreviews", long.class),
                "阶段0预览回读=成员只读路径（按 core 回显归属 assertCanView）");
        // The document list lane carries the server-side category filter and the sort pair
        // (sortBy/sortDir) on top of the paging window — pin the full current signature.
        assertNull(permOf(DocumentController.class, "list", long.class, Integer.class, String.class,
                String.class, int.class, int.class, String.class, String.class), "文档列表=成员只读路径");
        assertNull(permOf(DocumentController.class, "upload", long.class, String.class, MultipartFile.class),
                "上传=需求输入写路径（assertCanWriteRequirement 角色闸）");
        assertNull(permOf(DocumentController.class, "get", long.class), "文档详情=成员只读路径");
        assertNull(permOf(DocumentController.class, "delete", long.class), "删除=需求输入写路径");
        assertNull(permOf(DocumentController.class, "extract", long.class, ExtractRequest.class),
                "提取提交=F2 RUN 族命中集双向不等禁挂");
        assertNull(permOf(DocumentController.class, "extraction", long.class, int.class, int.class),
                "提取预览=成员只读路径");
        assertNull(permOf(DocumentController.class, "confirmExtraction", long.class,
                ConfirmExtractionRequest.class), "勾选入库=需求输入写路径");
        assertNull(permOf(DocumentController.class, "cancelExtraction", long.class),
                "取消提取=需求输入写路径（角色闸非键）");
        // Row-level category and usage-switch edits share the upload write door
        // (assertCanWriteRequirement); the single-chunk evidence read shares the preview
        // read door (assertCanView). Same exemption verdict as their siblings: no candidate
        // key expresses the role gate, so the endpoints stay annotation-free and registered.
        assertNull(permOf(DocumentController.class, "updateCategory", long.class,
                DocumentCategoryRequest.class), "行内改分类=需求输入写路径（角色闸非键）");
        assertNull(permOf(DocumentController.class, "updateUsageSwitches", long.class,
                DocumentUsageSwitchesRequest.class), "行内用途开关组=需求输入写路径（角色闸非键）");
        assertNull(permOf(DocumentController.class, "chunk", long.class, long.class), "原文分块回读=成员只读路径");
        // tail-f observation pair: both legs are read-only assertCanView lanes.
        assertNull(permOf(DocumentController.class, "extractionEvents",
                        long.class, long.class, Integer.class),
                "提取过程事件游标窗=成员只读路径");
        assertNull(permOf(DocumentController.class, "extractionEventStream",
                        long.class, long.class, jakarta.servlet.http.HttpServletResponse.class),
                "提取过程事件实时流=成员只读路径");

        // Assertion 2: an endpoint with no key must be registered as an exemption in the whitelist
        // file (no bare gaps) — every ai-domain endpoint sits on the exemption face.
        String[][] exempt = {
                {"POST", "/api/v1/ai/generate"},
                {"GET", "/api/v1/ai/tasks/7"},
                {"POST", "/api/v1/ai/tasks/7/retry"},
                {"POST", "/api/v1/ai/tasks/7/cancel"},
                {"POST", "/api/v1/ai/batch/estimate"},
                {"GET", "/api/v1/ai/batch/limit"},
                {"POST", "/api/v1/ai/batch-generate"},
                {"GET", "/api/v1/ai/batches/7"},
                {"GET", "/api/v1/ai/documents"},
                {"POST", "/api/v1/ai/documents"},
                {"GET", "/api/v1/ai/documents/7"},
                {"DELETE", "/api/v1/ai/documents/7"},
                {"POST", "/api/v1/ai/documents/7/extract"},
                {"GET", "/api/v1/ai/documents/7/extraction"},
                {"POST", "/api/v1/ai/documents/7/confirm-extraction"},
                {"POST", "/api/v1/ai/documents/7/confirm-all-extraction"},
                {"POST", "/api/v1/ai/documents/7/extraction-cancel"},
                {"PATCH", "/api/v1/ai/documents/7/category"},
                {"PATCH", "/api/v1/ai/documents/7/usage-switches"},
                {"GET", "/api/v1/ai/documents/7/chunks/3"},
                {"GET", "/api/v1/ai/documents/7/extraction-events"},
                {"GET", "/api/v1/ai/documents/7/extraction-events/stream"},
        };
        for (String[] ep : exempt) {
            assertTrue(PublicEndpointRegistry.isPublic(ep[0], ep[1]),
                    "ai 域豁免链路未登记豁免（裸缺口）：" + ep[0] + " " + ep[1]);
        }
    }

    @Test
    void 豁免模板段不得外溢误吞() {
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/ai/documents/abc"),
                "{id} 模板禁吞字面段（非数字永不路由到 handler=豁免面歧义盲区）");
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/ai/tasks/abc"),
                "taskId 亦数字语义（模板段收紧 {id} 后非数字不得豁免）");
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/ai/batches/abc"),
                "batchId 同上");
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/ai/tasks"),
                "模板端点不得豁免裸父路径");
        assertFalse(PublicEndpointRegistry.isPublic("DELETE", "/api/v1/ai/generate"),
                "方法敏感：DELETE /ai/generate 不在豁免面");
        assertFalse(PublicEndpointRegistry.isPublic("POST", "/api/v1/ai/documents/7/extraction"),
                "方法敏感：预览链路只读 GET，POST 不得误豁免");
    }

    @Test
    void 候选键族反证钉死纯豁免证据链() {
        Set<PermActionFamily> developerFamilies = RoleTemplate.DEVELOPER.families();
        assertFalse(developerFamilies.contains(PermActionFamily.CREATE),
                "developer 模板含 CREATE（反证前提勿改）");

        assertEquals(PermActionFamily.CREATE,
                PermissionCatalog.fromKey("perm:case:case-generate").family(), "A1 键族=CREATE（证据链勿改）");
        assertEquals(PermActionFamily.CREATE,
                PermissionCatalog.fromKey("perm:case:batch-generate").family(), "A2 键族=CREATE（证据链勿改）");
        assertEquals(PermActionFamily.CREATE,
                PermissionCatalog.fromKey("perm:document:doc-upload").family(), "F1 键族=CREATE（证据链勿改）");
        assertEquals(PermActionFamily.RUN,
                PermissionCatalog.fromKey("perm:document:requirement-extract").family(), "F2 键族=RUN（证据链勿改）");
        assertEquals(PermActionFamily.RUN,
                PermissionCatalog.fromKey("perm:document:api-doc-analyze").family(), "F4 键族=RUN（证据链勿改）");
        assertEquals(PermActionFamily.MANAGE,
                PermissionCatalog.fromKey("perm:document:doc-context-budget").family(), "F5 键族=MANAGE（证据链勿改）");
        assertEquals(PermActionFamily.VIEW,
                PermissionCatalog.fromKey("perm:document:change-detect").family(), "F3 键族=VIEW（禁借证据链勿改）");
    }
}
