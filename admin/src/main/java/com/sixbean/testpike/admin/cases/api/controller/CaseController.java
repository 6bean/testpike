package com.sixbean.testpike.admin.cases.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.batch.api.dto.BatchStartResultView;
import com.sixbean.testpike.admin.cases.api.dto.AssignBatchResultView;
import com.sixbean.testpike.admin.cases.api.dto.AssignCaseRequest;
import com.sixbean.testpike.admin.cases.api.dto.AssignResultView;
import com.sixbean.testpike.admin.cases.api.dto.AutoAssignRequest;
import com.sixbean.testpike.admin.cases.api.dto.AutoAssignResultView;
import com.sixbean.testpike.admin.cases.api.dto.BatchAssignRequest;
import com.sixbean.testpike.admin.cases.api.dto.BatchEditRequest;
import com.sixbean.testpike.admin.cases.api.dto.CaseExportResultView;
import com.sixbean.testpike.admin.cases.api.dto.CaseReviewLogView;
import com.sixbean.testpike.admin.cases.api.dto.CaseRecycleView;
import com.sixbean.testpike.admin.cases.api.dto.CaseRollbackResultView;
import com.sixbean.testpike.admin.cases.api.dto.CaseVersionDiffView;
import com.sixbean.testpike.admin.cases.api.dto.CaseVersionView;
import com.sixbean.testpike.admin.cases.api.dto.CloneCaseRequest;
import com.sixbean.testpike.admin.cases.api.dto.BatchEditResultView;
import com.sixbean.testpike.admin.cases.api.dto.BatchReviewRequest;
import com.sixbean.testpike.admin.cases.api.dto.BatchReviewResultView;
import com.sixbean.testpike.admin.cases.api.dto.CaseView;
import com.sixbean.testpike.admin.cases.api.dto.CreateCaseRequest;
import com.sixbean.testpike.admin.cases.api.dto.ExportCasesRequest;
import com.sixbean.testpike.admin.cases.api.dto.QuickExecuteRequest;
import com.sixbean.testpike.admin.cases.api.dto.RestoreCaseRequest;
import com.sixbean.testpike.admin.cases.api.dto.ReviewCaseRequest;
import com.sixbean.testpike.admin.cases.api.dto.ReviewLockView;
import org.springframework.http.ResponseEntity;
import com.sixbean.testpike.admin.cases.api.dto.SetFlakyRequest;
import com.sixbean.testpike.admin.cases.api.dto.ReviewResultView;
import com.sixbean.testpike.admin.cases.api.dto.RollbackCaseRequest;
import com.sixbean.testpike.admin.cases.api.dto.SetExcellentRequest;
import com.sixbean.testpike.admin.cases.api.dto.UpdateCaseRequest;
import com.sixbean.testpike.admin.cases.infrastructure.remote.client.CoreCaseClient;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.tags.api.dto.SetCaseTagsRequest;
import com.sixbean.testpike.admin.tags.api.dto.TagView;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.idempotent.IdempotentGuard;


import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/api/v1/cases")
public class CaseController {

    private final CoreCaseClient coreCaseClient;
    private final com.sixbean.testpike.admin.rbac.infrastructure.remote.client.CoreRoleClient coreRoleClient;
    private final com.sixbean.testpike.admin.tags.infrastructure.remote.client.CoreTagClient coreTagClient;
    private final com.sixbean.testpike.admin.requirement.infrastructure.remote.client.CoreRequirementClient coreRequirementClient;
    private final ProjectAccessGuard guard;
    private final IdempotentGuard idempotentGuard;

    public CaseController(CoreCaseClient coreCaseClient, ProjectAccessGuard guard,
                          com.sixbean.testpike.admin.rbac.infrastructure.remote.client.CoreRoleClient coreRoleClient,
                          com.sixbean.testpike.admin.tags.infrastructure.remote.client.CoreTagClient coreTagClient,
                          com.sixbean.testpike.admin.requirement.infrastructure.remote.client.CoreRequirementClient coreRequirementClient,
                          IdempotentGuard idempotentGuard) {
        this.coreCaseClient = coreCaseClient;
        this.coreRoleClient = coreRoleClient;
        this.coreTagClient = coreTagClient;
        this.coreRequirementClient = coreRequirementClient;
        this.guard = guard;
        this.idempotentGuard = idempotentGuard;
    }

    
    @GetMapping
    public Response<PageView<CaseView>> list(@RequestParam(defaultValue = "0") long projectId,
                                             @RequestParam(required = false) Integer status,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) Integer priority,
                                             @RequestParam(required = false) Integer confidence,
                                             @RequestParam(required = false) Long assigneeId,
                                             @RequestParam(required = false) Integer phase,
                                             @RequestParam(required = false) List<Long> tagIds,
                                             @RequestParam(required = false) String sortBy,
                                             @RequestParam(required = false) String sortDir,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "10") int size,
                                             @RequestParam(required = false) Boolean excellentOnly,
                                             @RequestParam(defaultValue = "0") long programId,
                                             @RequestParam(required = false) String sourceFacet,
                                             @RequestParam(required = false) Integer evidenceGrade) {
        if (projectId == 0L) {
            if (programId == 0L) {
                // Cross-project view without a lens: managers only (data scope = all).
                if (coreRoleClient.dataScopeOfUser(UserContext.currentUserId()) != 1) {
                    throw new ServiceException("仅数据范围为「全部项目」的角色可跨项目查看用例，"
                            + "可先在顶栏选择具体项目，或由管理员在「角色管理」把角色数据范围改为「全部项目」",
                            BizErrorCode.FORBIDDEN);
                }
            }
            // With a lens: core narrows to lens projects ∩ the caller's visible domain
            // (ProgramLensScope single source), so no manager gate is needed here.
        } else {
            guard.assertCanView(UserContext.current(), projectId);
        }
        PageView<CaseView> result = coreCaseClient.listCases(projectId, status, keyword, priority,
                confidence, assigneeId, phase, tagIds, sortBy, sortDir, page, size, excellentOnly,
                programId, sourceFacet, evidenceGrade);
        if (projectId > 0 && result != null && result.list() != null && !result.list().isEmpty()) {
            Map<Long, List<TagView>> tagsByCase = coreTagClient.tagsByCases(projectId,
                    result.list().stream().map(CaseView::id).toList());
            java.util.Set<Long> offlineIds = new java.util.HashSet<>(
                    coreRequirementClient.offlineCaseIds(projectId,
                            result.list().stream().map(CaseView::id).toList()));
            List<CaseView> enriched = result.list().stream()
                    .map(cv -> cv.withTags(tagsByCase.getOrDefault(cv.id(), List.of()))
                            .withOfflineLinked(offlineIds.contains(cv.id())))
                    .toList();
            return Response.builderSuccess(new PageView<>(enriched, result.total()));
        }
        return Response.builderSuccess(result);
    }

    /**
 * Pending flaky restore proposals (AO batch read channel,
 * GET /cases/flaky-proposals?projectId=): state-1 re-probe ledger sessions of the
 * project, surfaced for human confirmation. Viewing permission is the same project
 * member check as the case list; clearing the flaky mark stays PUT /cases/{id}/flaky.
 */
    @GetMapping("/flaky-proposals")
    public Response<List<com.sixbean.testpike.admin.cases.api.dto.FlakyRestoreProposalView>> flakyProposals(
            @RequestParam long projectId) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(coreCaseClient.flakyRestoreProposals(projectId));
    }

    
    @GetMapping("/{id}")
    public Response<CaseView> get(@PathVariable long id) {
        CaseView view = coreCaseClient.getCase(id);
        guard.assertCanView(UserContext.current(), view.projectId());
        return Response.builderSuccess(view.withTags(coreTagClient.caseTags(id)));
    }

 
    @GetMapping("/{id}/references")
    public Response<List<String>> checkReferences(@PathVariable long id) {
        CaseView view = coreCaseClient.getCase(id);
        guard.assertCanView(UserContext.current(), view.projectId());
        return Response.builderSuccess(coreCaseClient.checkReferences(id));
    }

 
    @GetMapping("/{id}/review-logs")
    @RequirePermission("perm:ux:review-efficiency")
    public Response<List<CaseReviewLogView>> listReviewLogs(@PathVariable long id) {
        CaseView view = coreCaseClient.getCase(id);
        guard.assertCanView(UserContext.current(), view.projectId());
        return Response.builderSuccess(coreCaseClient.listReviewLogs(id));
    }

 
    @GetMapping("/{id}/versions")
    @RequirePermission("perm:scenario:version-history")
    public Response<List<CaseVersionView>> listVersions(@PathVariable long id) {
        CaseView view = coreCaseClient.getCase(id);
        guard.assertCanView(UserContext.current(), view.projectId());
        return Response.builderSuccess(coreCaseClient.listVersions(id));
    }

    
    @GetMapping("/{id}/versions/{v}/diff")
    @RequirePermission("perm:scenario:version-history")
    public Response<CaseVersionDiffView> diffVersion(@PathVariable long id, @PathVariable int v,
                                                     @RequestParam(required = false) Integer baseVersion) {
        CaseView view = coreCaseClient.getCase(id);
        guard.assertCanView(UserContext.current(), view.projectId());
        return Response.builderSuccess(coreCaseClient.diffVersion(id, v, baseVersion));
    }

    
    @PostMapping("/{id}/versions/{v}/rollback")
    @RequirePermission("perm:scenario:version-history")
    public Response<CaseRollbackResultView> rollback(@PathVariable long id, @PathVariable int v,
                                                     @RequestBody RollbackCaseRequest request) {
        CaseView view = coreCaseClient.getCase(id);
        guard.assertCanWriteCase(UserContext.current(), view.projectId());
        return Response.builderSuccess(coreCaseClient.rollback(id, v, request.version()));
    }

 
    @PutMapping("/{id}/flaky")
    @RequirePermission("perm:scenario:flaky-isolate")
    public Response<CaseView> setFlaky(@PathVariable long id, @RequestBody SetFlakyRequest request) {
        CaseView view = coreCaseClient.getCase(id);
        guard.assertCanWriteCase(UserContext.current(), view.projectId());
        if (request == null || request.isolated() == null) {
            throw new ServiceException("缺少隔离标记（isolated），请在请求体中补充该字段，"
                    + "示例：{\"isolated\": true}", BizErrorCode.VALIDATION_ERROR);
        }
        return Response.builderSuccess(coreCaseClient.setFlaky(id, request.isolated(), request.reason()));
    }

    
    @PutMapping("/{id}/excellent")
    @RequirePermission("perm:feedback:excellent-mark")
    public Response<CaseView> markExcellent(@PathVariable long id, @RequestBody SetExcellentRequest request) {
        CaseView view = coreCaseClient.getCase(id);
        guard.assertCanWriteCase(UserContext.current(), view.projectId());
        if (request == null || request.excellent() == null) {
            throw new ServiceException("缺少优秀标记（excellent），请在请求体中补充该字段，"
                    + "示例：{\"excellent\": true}", BizErrorCode.VALIDATION_ERROR);
        }
        if (request.version() == null) {
            throw new ServiceException("缺少乐观锁版本号（version），请携带用例当前版本号重试，"
                    + "示例：{\"version\": 12}", BizErrorCode.VALIDATION_ERROR);
        }
        return Response.builderSuccess(coreCaseClient.setExcellent(id, request.excellent(), request.version()));
    }

    
    @PostMapping("/{id}/quick-execute")
    @RequirePermission("perm:batch:quick-run")
    public Response<BatchStartResultView> quickExecute(@PathVariable long id,
                                                       @RequestBody(required = false) QuickExecuteRequest request) {
        CaseView view = coreCaseClient.getCase(id);
        guard.assertCanWriteCase(UserContext.current(), view.projectId());
        Long envId = request == null ? null : request.envId();
        return Response.builderSuccess(coreCaseClient.quickExecute(id, envId,
                request == null ? null : request.browser()));
    }

    
    @PostMapping("/{id}/clone")
    @RequirePermission("perm:case:case-clone")
    public Response<CaseView> clone(@PathVariable long id,
                                    @RequestBody(required = false) CloneCaseRequest request) {
        CurrentUser user = UserContext.current();
        CaseView view = coreCaseClient.getCase(id);
        guard.assertCanWriteCase(user, view.projectId());
        Long targetProjectId = request == null ? null : request.targetProjectId();
        if (targetProjectId != null && !targetProjectId.equals(view.projectId())) {
            guard.assertCanWriteCase(user, targetProjectId);
        }
        return Response.builderSuccess(coreCaseClient.cloneCase(id, targetProjectId));
    }

 
    @PutMapping("/{id}/tags")
    @RequirePermission("perm:detail:case-tag")
    public Response<List<TagView>> setCaseTags(@PathVariable long id,
                                               @RequestBody SetCaseTagsRequest request) {
        CaseView view = coreCaseClient.getCase(id);
        guard.assertCanView(UserContext.current(), view.projectId());
        guard.assertCanWriteCase(UserContext.current(), view.projectId());
        return Response.builderSuccess(
                coreTagClient.setCaseTags(id, view.projectId(), request.tagIds()));
    }

    
    @PostMapping
    public Response<CaseView> create(@RequestBody CreateCaseRequest request) {
        guard.assertCanWriteCase(UserContext.current(), request.projectId());
        return Response.builderSuccess(coreCaseClient.createCase(request));
    }

    
    @PutMapping("/{id}")
    public Response<CaseView> update(@PathVariable long id, @RequestBody UpdateCaseRequest request) {
        CaseView view = coreCaseClient.getCase(id);
        guard.assertCanWriteCase(UserContext.current(), view.projectId());
        return Response.builderSuccess(coreCaseClient.updateCase(id, request));
    }

    
    @DeleteMapping("/{id}")
    public Response<Long> delete(@PathVariable long id) {
        CaseView view = coreCaseClient.getCase(id);
        guard.assertCanDeleteCase(UserContext.current(), view.projectId());
        return Response.builderSuccess(coreCaseClient.deleteCase(id));
    }

    
    @PostMapping("/export")
    @RequirePermission("perm:ux:case-export")
    public Response<CaseExportResultView> export(@RequestBody ExportCasesRequest request) {
        guard.assertCanExportCase(UserContext.current(), request.projectId());
        return Response.builderSuccess(coreCaseClient.exportCases(request));
    }

    
    @GetMapping("/recycle-bin")
    @RequirePermission("perm:ux:trash-restore")
    public Response<PageView<CaseRecycleView>> recycleBin(@RequestParam long projectId,
                                                          @RequestParam(required = false) String keyword,
                                                          @RequestParam(defaultValue = "1") int page,
                                                          @RequestParam(defaultValue = "10") int size) {
        guard.assertCanDeleteCase(UserContext.current(), projectId);
        return Response.builderSuccess(coreCaseClient.listRecycleBin(projectId, keyword, page, size));
    }

    
    @PutMapping("/{id}/restore")
    @RequirePermission("perm:ux:trash-restore")
    public Response<CaseView> restore(@PathVariable long id, @RequestBody RestoreCaseRequest request) {
        CaseRecycleView entry = coreCaseClient.recycleEntry(id);
        guard.assertCanDeleteCase(UserContext.current(), entry.projectId());
        return Response.builderSuccess(coreCaseClient.restoreCase(id, request.version()));
    }

 
    @DeleteMapping("/{id}/purge")
    @RequirePermission("perm:ux:trash-restore")
    public Response<Long> purgeCase(@PathVariable long id) {
        CaseRecycleView entry = coreCaseClient.recycleEntry(id);
        guard.assertCanDeleteCase(UserContext.current(), entry.projectId());
        return Response.builderSuccess(coreCaseClient.purgeCase(id));
    }

 
    @DeleteMapping("/recycle-bin")
    @RequirePermission("perm:ux:trash-restore")
    public Response<Long> clearRecycleBin(@RequestParam long projectId) {
        guard.assertCanDeleteCase(UserContext.current(), projectId);
        return Response.builderSuccess(coreCaseClient.clearRecycleBin(projectId));
    }

    
    @PostMapping("/{id}/review")
    public Response<ReviewResultView> review(@PathVariable long id,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                             @RequestBody ReviewCaseRequest request) {
        CaseView view = coreCaseClient.getCase(id);
        guard.assertCanReview(UserContext.current(), view.projectId(), view.assigneeId());
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Response.builderSuccess(coreCaseClient.reviewCase(id, request));
        }
        String key = "review:" + idempotencyKey.trim() + ":" + UserContext.currentUserId() + ":" + id;
        return Response.builderSuccess(idempotentGuard.execute(key, () -> coreCaseClient.reviewCase(id, request)));
    }

    
    @PostMapping("/{id}/review-lock")
    public Response<ReviewLockView> acquireReviewLock(@PathVariable long id) {
        CaseView view = coreCaseClient.getCase(id);
        guard.assertCanReview(UserContext.current(), view.projectId(), view.assigneeId());
        return Response.builderSuccess(coreCaseClient.acquireReviewLock(id));
    }

    
    @PutMapping("/{id}/review-lock/heartbeat")
    public Response<ReviewLockView> heartbeatReviewLock(@PathVariable long id) {
        CaseView view = coreCaseClient.getCase(id);
        guard.assertCanReview(UserContext.current(), view.projectId(), view.assigneeId());
        return Response.builderSuccess(coreCaseClient.heartbeatReviewLock(id));
    }

    
    @DeleteMapping("/{id}/review-lock")
    public ResponseEntity<Void> releaseReviewLock(@PathVariable long id) {
        CaseView view = coreCaseClient.getCase(id);
        guard.assertCanReview(UserContext.current(), view.projectId(), view.assigneeId());
        coreCaseClient.releaseReviewLock(id);
        return ResponseEntity.noContent().build();
    }

    
    @PostMapping("/{id}/assign")
    public Response<AssignResultView> assign(@PathVariable long id, @RequestBody AssignCaseRequest request) {
        CaseView view = coreCaseClient.getCase(id);
        guard.assertCanAssignCase(UserContext.current(), view.projectId());
        return Response.builderSuccess(coreCaseClient.assignCase(id, request.assigneeId()));
    }

    
    @PostMapping("/batch-review")
    public Response<BatchReviewResultView> batchReview(@RequestBody BatchReviewRequest request) {
        List<Long> ids = request.caseIds();
        if (ids == null || ids.isEmpty()) {
            throw new ServiceException("请选择至少一条用例进行评审，"
                    + "可在「测试用例」列表勾选行后再点「批量评审」", BizErrorCode.VALIDATION_ERROR);
        }
        CaseView first = coreCaseClient.getCase(ids.get(0));
        guard.assertCanReview(UserContext.current(), first.projectId(), null);
        return Response.builderSuccess(coreCaseClient.batchReviewCases(first.projectId(),
                request.action(), request.remark(), ids, request.confirmLowConfidence(),
                request.feedbackTypes()));
    }

    
    @PostMapping("/batch-assign")
    public Response<AssignBatchResultView> batchAssign(@RequestBody BatchAssignRequest request) {
        guard.assertCanAssignCase(UserContext.current(), request.projectId());
        return Response.builderSuccess(coreCaseClient.batchAssignCases(
                request.projectId(), request.caseIds(), request.assigneeId()));
    }

    
    @PostMapping("/auto-assign")
    @RequirePermission("perm:review:auto-assign")
    public Response<AutoAssignResultView> autoAssign(@RequestBody AutoAssignRequest request) {
        guard.assertCanAssignCase(UserContext.current(), request.projectId());
        return Response.builderSuccess(coreCaseClient.autoAssign(
                request.projectId(), request.caseIds(), request.preview()));
    }

    
    @PostMapping("/batch-edit")
    @RequirePermission("perm:security:case-batch-edit")
    public Response<BatchEditResultView> batchEdit(@RequestBody BatchEditRequest request) {
        if (request.caseIds() == null || request.caseIds().isEmpty()) {
            throw new ServiceException("请选择至少一条用例进行编辑，"
                    + "可在「测试用例」列表勾选行后再点「批量编辑」", BizErrorCode.VALIDATION_ERROR);
        }
        guard.assertCanWriteCase(UserContext.current(), request.projectId());
        return Response.builderSuccess(coreCaseClient.batchEditCases(
                request.projectId(), request.caseIds(),
                request.phase(), request.priority(), request.tagIds()));
    }
}
