package com.sixbean.testpike.core.cases.api.controller;

import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.batch.api.dto.BatchStartResult;
import com.sixbean.testpike.core.cases.api.dto.AssignBatchRequest;
import com.sixbean.testpike.core.cases.api.dto.AssignBatchResult;
import com.sixbean.testpike.core.cases.api.dto.AssignRequest;
import com.sixbean.testpike.core.cases.api.dto.AssignResult;
import com.sixbean.testpike.core.cases.api.dto.QuickExecuteRequest;
import com.sixbean.testpike.core.cases.api.dto.BatchEditRequest;
import com.sixbean.testpike.core.cases.api.dto.BatchEditResult;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseExcellentRequest;
import com.sixbean.testpike.core.cases.api.dto.CaseExportRequest;
import com.sixbean.testpike.core.cases.api.dto.CaseExportResult;
import com.sixbean.testpike.core.cases.api.dto.CaseFlakyRequest;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseQuery;
import com.sixbean.testpike.core.cases.api.dto.CaseRecycleInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseReviewLogInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseRollbackRequest;
import com.sixbean.testpike.core.cases.api.dto.CaseRollbackResult;
import com.sixbean.testpike.core.cases.api.dto.CaseUpdateRequest;
import com.sixbean.testpike.core.cases.api.dto.CloneCaseRequest;
import com.sixbean.testpike.core.cases.api.dto.CaseVersionDiff;
import com.sixbean.testpike.core.cases.api.dto.CaseVersionInfo;
import com.sixbean.testpike.core.cases.api.dto.DailyDigestResult;
import com.sixbean.testpike.core.cases.api.dto.FlakyDetectionResult;
import com.sixbean.testpike.core.cases.api.dto.RestoreCaseRequest;
import com.sixbean.testpike.core.cases.api.dto.ReviewRemindResult;
import com.sixbean.testpike.core.cases.api.dto.ReviewBatchRequest;
import com.sixbean.testpike.core.cases.api.dto.ReviewBatchResult;
import com.sixbean.testpike.core.cases.api.dto.ReviewLockInfo;
import com.sixbean.testpike.core.cases.api.dto.ReviewRequest;
import com.sixbean.testpike.core.cases.api.dto.ReviewResult;
import com.sixbean.testpike.core.cases.domain.component.ReviewAction;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.cases.domain.service.CaseFlowGuideService;
import com.sixbean.testpike.core.cases.domain.service.ReviewLockService;
import com.sixbean.testpike.core.cases.domain.service.DailyDigestService;
import com.sixbean.testpike.core.common.clientobject.PageResult;


import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import com.sixbean.testpike.core.cases.domain.service.ReviewRemindService;

import java.util.List;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal test-case endpoints in the core service layer are called by admin over HTTP for /cases.
 * Permission policy is enforced on the admin side, where project members can access, non-developers are read-only, deletion is owner-only, and 403 semantics remain unchanged.
 * Operator is passed through from X-Operator-Id to close the audit created_by loop.
 *
 */
@RestController
@RequestMapping("/internal/cases")
public class CaseInternalController {

    private final CaseService caseService;
    private final CaseFlowGuideService caseFlowGuideService;
    private final com.sixbean.testpike.core.cases.domain.service.CaseVersionCompareService caseVersionCompareService;
    private final ReviewRemindService reviewRemindService;
    private final DailyDigestService dailyDigestService;
    private final ReviewLockService reviewLockService;
    private final com.sixbean.testpike.core.batch.domain.service.BatchService batchService;
    private final com.sixbean.testpike.core.cases.domain.service.CaseAutoAssignService caseAutoAssignService;
    private final com.sixbean.testpike.core.cases.domain.service.FlakyProbeService flakyProbeService;

    public CaseInternalController(CaseService caseService,
                                  CaseFlowGuideService caseFlowGuideService,
                                  com.sixbean.testpike.core.cases.domain.service.CaseVersionCompareService caseVersionCompareService,
                                  ReviewRemindService reviewRemindService,
                                  DailyDigestService dailyDigestService,
                                  ReviewLockService reviewLockService,
                                  com.sixbean.testpike.core.batch.domain.service.BatchService batchService,
                                  com.sixbean.testpike.core.cases.domain.service.CaseAutoAssignService caseAutoAssignService,
                                  com.sixbean.testpike.core.cases.domain.service.FlakyProbeService flakyProbeService) {
        this.caseService = caseService;
        this.caseFlowGuideService = caseFlowGuideService;
        this.caseVersionCompareService = caseVersionCompareService;
        this.reviewRemindService = reviewRemindService;
        this.dailyDigestService = dailyDigestService;
        this.reviewLockService = reviewLockService;
        this.batchService = batchService;
        this.caseAutoAssignService = caseAutoAssignService;
        this.flakyProbeService = flakyProbeService;
    }

    /** Test-case list supports filtering, sorting, pagination, and data-scope filtering, and when projectId is 0, only roles whose data scope is all may query across projects. */
    @GetMapping
    public Response<PageResult<CaseInfo>> list(@RequestParam(defaultValue = "0") long projectId,
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
        return Response.builderSuccess(caseService.list(
                new CaseQuery(projectId, status, keyword, priority, confidence,
                        assigneeId, phase, tagIds, sortBy, sortDir, page, size, excellentOnly,
                        programId, sourceFacet, evidenceGrade),
                OperatorContext.currentOperatorId()));
    }

    /** Test-case details include steps. */
    @GetMapping("/{id}")
    public Response<CaseInfo> get(@PathVariable long id) {
        return Response.builderSuccess(caseService.get(id));
    }

    /**
     * Flow guide (AO-9 E8): "stuck at some status — what can I do next".
     * Read-only; caseId optional (absent = whole status vocabulary map).
     * Consumed by the dialog tool over the internal HTTP channel.
     */
    @GetMapping("/flow-guide")
    public Response<com.sixbean.testpike.core.cases.api.dto.FlowGuideInfo> flowGuide(
            @RequestParam(required = false) Long caseId) {
        return Response.builderSuccess(caseFlowGuideService.guide(caseId));
    }

    /**
     * POST twin of the GET door (AO-9 stage 2): the built-in case.flow-guide
     * tool is dialed over the HTTP exec form, which always POSTs the model
     * input as the body. Delegates to the same service — one read face, two
     * transport shapes, no second validation path.
     */
    @PostMapping("/flow-guide")
    public Response<com.sixbean.testpike.core.cases.api.dto.FlowGuideInfo> flowGuidePost(
            @RequestBody(required = false) com.sixbean.testpike.core.cases.api.dto.FlowGuideRequest body) {
        return Response.builderSuccess(
                caseFlowGuideService.guide(body == null ? null : body.caseId()));
    }

    /**
 * One-line compare card for the dialog :
 * POST door dialed by the built-in case.version-compare read tool. The
 * summary is assembled server-side from the SHARED diff door
 * (CaseService.diffVersion) and carries field labels plus stepNo-aligned
 * step counts only — never raw before/after values, which is what keeps
 * the card a single human line instead of a JSON dump. Anchor validation
 * lives in the service, the controller stays thin.
 */
    @PostMapping("/version-compare")
    public Response<com.sixbean.testpike.core.cases.api.dto.CaseVersionCompareInfo> versionComparePost(
            @RequestBody(required = false) com.sixbean.testpike.core.cases.api.dto.CaseVersionCompareRequest body) {
        return Response.builderSuccess(caseVersionCompareService.compare(
                body == null ? null : body.caseId(),
                body == null ? null : body.fromVersion(),
                body == null ? null : body.toVersion()));
    }

 /** When a test case is referenced by plans, the response returns the names of referencing uncanceled plans. */
    @GetMapping("/{id}/references")
    public Response<List<String>> checkReferences(@PathVariable long id) {
        return Response.builderSuccess(caseService.checkReferences(id));
    }

    /**
 * Test-case review history for the context sidebar is exposed via GET /cases/{id}/review-logs.
     * Results are ordered by time descending, operator display names are resolved on the core side, and project-member permission is checked on the admin side.
     */
    @GetMapping("/{id}/review-logs")
    public Response<List<CaseReviewLogInfo>> reviewLogs(@PathVariable long id) {
        return Response.builderSuccess(caseService.listReviewLogs(id));
    }

    /**
 * Version-history list is exposed via GET /cases/{id}/versions.
     * Versions are ordered descending, and permission is checked on the admin side.
     */
    @GetMapping("/{id}/versions")
    public Response<List<CaseVersionInfo>> versions(@PathVariable long id) {
        return Response.builderSuccess(caseService.listVersions(id));
    }

    /** Field-level version diff is exposed via GET /cases/{id}/versions/{v}/diff?baseVersion=, defaulting the base version to the latest version. */
    @GetMapping("/{id}/versions/{v}/diff")
    public Response<CaseVersionDiff> versionDiff(@PathVariable long id, @PathVariable int v,
                                                 @RequestParam(required = false) Integer baseVersion) {
        return Response.builderSuccess(caseService.diffVersion(id, v, baseVersion));
    }

    /** Rollback creates a new version from the old version content via POST /cases/{id}/versions/{v}/rollback without altering history. */
    @PostMapping("/{id}/versions/{v}/rollback")
    public Response<CaseRollbackResult> rollback(@PathVariable long id, @PathVariable int v,
                                                 @RequestBody CaseRollbackRequest request) {
        if (request == null || request.version() == null) {
            throw new ServiceException("缺少乐观锁版本号（version）——请在「用例管理」的版本历史重新点击回滚"
                        + "（示例：请求体 {\"version\":3}）", BizErrorCode.VALIDATION_ERROR);
        }
        return Response.builderSuccess(caseService.rollback(
                OperatorContext.currentOperatorId(), id, v, request.version()));
    }

    /** Flaky isolation and release are handled via PUT /cases/{id}/flaky with body {isolated, reason}, and test-case edit permission is checked on the admin side. */
    @PutMapping("/{id}/flaky")
    public Response<CaseInfo> setFlaky(@PathVariable long id, @RequestBody CaseFlakyRequest request) {
        if (request == null || request.isolated() == null) {
            throw new ServiceException("缺少隔离标记（isolated）——请在「用例管理」的不稳定用例开关重新提交"
                        + "（示例：请求体 {\"isolated\":true,\"reason\":\"环境抖动\"}）", BizErrorCode.VALIDATION_ERROR);
        }
        return Response.builderSuccess(caseService.setFlaky(
                OperatorContext.currentOperatorId(), id, request.isolated(), request.reason()));
    }

    /**
 * Pending flaky restore proposals for the project (AO batch read
 * channel): state-1 ledger sessions only, strictly zero-write, view permission is
 * checked on the admin side.
 */
    @GetMapping("/flaky-proposals")
    public Response<List<com.sixbean.testpike.core.cases.api.dto.FlakyRestoreProposal>> flakyProposals(
            @RequestParam long projectId) {
        return Response.builderSuccess(flakyProbeService.pendingProposals(projectId));
    }

 /** Marking or unmarking an excellent test case uses PUT /cases/{id}/excellent with body {excellent, version}, and permission is checked on the admin side. */
    @PutMapping("/{id}/excellent")
    public Response<CaseInfo> markExcellent(@PathVariable long id,
                                            @RequestBody CaseExcellentRequest request) {
        if (request == null || request.excellent() == null) {
            throw new ServiceException("缺少优秀标记（excellent）——请在「用例管理」的用例行重新点击标星"
                        + "（示例：请求体 {\"excellent\":true,\"version\":当前版本号}）", BizErrorCode.VALIDATION_ERROR);
        }
        if (request.version() == null) {
            throw new ServiceException("缺少乐观锁版本号（version）——请在「用例管理」刷新用例后重新提交标星"
                    + "（示例：请求体 {\"excellent\":true,\"version\":3}）", BizErrorCode.VALIDATION_ERROR);
        }
        return Response.builderSuccess(caseService.markExcellent(
                OperatorContext.currentOperatorId(), id, request.excellent(), request.version()));
    }

    /**
 * Quick execution via POST /cases/{id}/quick-execute automatically creates a temporary batch and starts it.
     * The response immediately returns BatchStartResult, write-test-case permission is guarded on the admin side, and the core side only persists the internal bridge.
     */
    @PostMapping("/{id}/quick-execute")
    public Response<BatchStartResult> quickExecute(@PathVariable long id,
                                                   @RequestBody(required = false) QuickExecuteRequest request) {
        return Response.builderSuccess(batchService.quickExecute(
                OperatorContext.currentOperatorId(), id,
                request == null ? null : request.envId(),
                request == null ? null : request.browser()));
    }

    /**
 * Acquiring a review lock uses POST /cases/{id}/review-lock and bridges internally.
     * Lock semantics in ReviewLockService use a 30-minute TTL, return 409 with the lock holder when occupied, and require an admin-side pre-check for permission.
     * The operator is the current user ID, with X-Operator-Id passed through and interpreted as ownerId.
     */
    @PostMapping("/{id}/review-lock")
    public Response<ReviewLockInfo> acquireReviewLock(@PathVariable long id) {
        return Response.builderSuccess(reviewLockService.acquire(id, requireOperatorId()));
    }

    /** Review-lock heartbeat renewal uses PUT /cases/{id}/review-lock/heartbeat, and 409 responses for non-holders or missing locks are passed through. */
    @PutMapping("/{id}/review-lock/heartbeat")
    public Response<ReviewLockInfo> heartbeatReviewLock(@PathVariable long id) {
        return Response.builderSuccess(reviewLockService.heartbeat(id, requireOperatorId()));
    }

    /** Releasing a review lock uses DELETE /cases/{id}/review-lock and is idempotent, with no side effects for non-holders.
     *  Core internally returns a Response envelope within the unified admin-to-core call chain, while admin preserves the external 204 empty-body contract. */
    @DeleteMapping("/{id}/review-lock")
    public Response<Void> releaseReviewLock(@PathVariable long id) {
        reviewLockService.release(id, requireOperatorId());
        return Response.builderSuccess();
    }

    /** The operator is required because lock-owner determination depends on the real userId, and a missing operator fails fast with 40001 due to a broken bridge chain. */
    private long requireOperatorId() {
        Long operatorId = OperatorContext.currentOperatorId();
        if (operatorId == null) {
            throw new ServiceException("缺少操作人上下文（X-Operator-Id）——请重新登录后再操作；接口直连调用"
                    + "请在请求头携带 X-Operator-Id（示例：X-Operator-Id: 1001）", BizErrorCode.VALIDATION_ERROR);
        }
        return operatorId;
    }

    /**
 * Test-case cloning uses POST /cases/{id}/clone with optional body {targetProjectId?}.
     * The default target is the same project, the response reuses the CaseInfo shape from create with no new contract, and validation covers source 404 and target 404.
     * Non-member 403 checks fail fast in CaseService.clone, with write-test-case permission guarded on admin and fallback-checked on core.
     */
    @PostMapping("/{id}/clone")
    public Response<CaseInfo> cloneCase(@PathVariable long id,
                                        @RequestBody(required = false) CloneCaseRequest request) {
        return Response.builderSuccess(caseService.clone(
                OperatorContext.currentOperatorId(), id, request == null ? null : request.targetProjectId()));
    }

    /**
     * Flaky detection is registered as an internal task with no external interface, and this endpoint is internal to core only.
     * It is used for manual or L4 validation triggering outside the daily scheduler, and admin neither bridges nor exposes it externally.
     */
    @PostMapping("/detect-flaky")
    public Response<FlakyDetectionResult> detectFlaky() {
        return Response.builderSuccess(caseService.detectFlaky());
    }

    /**
 * Review reminder scanning is registered as a server-side scheduled task with no new external interface, and this endpoint is internal only.
     * This core-internal endpoint is used for manual or L4 validation triggering outside ReviewRemindScheduler, and admin neither bridges nor exposes it externally.
     */
    @PostMapping("/review-remind-scan")
    public Response<ReviewRemindResult> reviewRemindScan() {
        return Response.builderSuccess(reviewRemindService.scan());
    }

    /**
 * Daily digest scanning is registered as a server-side scheduled task with no new external interface, and this endpoint is internal only.
     * This core-internal endpoint is used for manual, L4, or E2E validation triggering outside DailyDigestScheduler, and admin neither bridges nor exposes it externally.
     */
    @PostMapping("/daily-digest-scan")
    public Response<DailyDigestResult> dailyDigestScan() {
        return Response.builderSuccess(dailyDigestService.scan());
    }

    /** Creating a test case uses the cross-project unique identifier TC-{code}-{seq}. */
    @PostMapping
    public Response<CaseInfo> create(@RequestBody CaseCreateInput input) {
        return Response.builderSuccess(caseService.create(OperatorContext.currentOperatorId(), input));
    }

    /** Field-level updates use version-based optimistic locking and return 40901 on version conflict. */
    @PutMapping("/{id}")
    public Response<CaseInfo> update(@PathVariable long id, @RequestBody CaseUpdateRequest request) {
        return Response.builderSuccess(caseService.update(
                OperatorContext.currentOperatorId(), id, request.input(), request.version()));
    }

    /** Soft-delete test cases. */
    @DeleteMapping("/{id}")
    public Response<Long> delete(@PathVariable long id) {
        return Response.builderSuccess(caseService.delete(OperatorContext.currentOperatorId(), id));
    }

    /**
 * Test-case batch export uses POST /cases/export mapped to internal POST /internal/cases/export.
     * ids/filter one-of validation, count exclusion, formula-injection escaping, and auditing are all performed in CaseService.export.
     * Synchronous responses return Excel bytes in base64, and when there are more than 2000 records, async=true routes the request to the export-task queue with download reuse.
     * The download reuses GET /internal/reports/export-tasks/{id}/download, and duplicate implementations are prohibited.
     */
    @PostMapping("/export")
    public Response<CaseExportResult> export(@RequestBody CaseExportRequest request) {
        return Response.builderSuccess(caseService.export(OperatorContext.currentOperatorId(), request));
    }

    /**
 * Recycle-bin list uses GET /cases/recycle-bin mapped to internal GET /internal/cases/recycle-bin.
     * Deleted records are paginated with deleted=1, isolation is project-level, and view permission has already been checked on the admin side.
     */
    @GetMapping("/recycle-bin")
    public Response<PageResult<CaseRecycleInfo>> recycleBin(@RequestParam long projectId,
                                                            @RequestParam(required = false) String keyword,
                                                            @RequestParam(defaultValue = "1") int page,
                                                            @RequestParam(defaultValue = "10") int size) {
        return Response.builderSuccess(caseService.recycleBin(projectId, keyword, page, size));
    }

    /** Recycle bin single row performs an upfront admin permission check and retrieves the soft-deleted row projectId for restore or permanent delete. */
    @GetMapping("/{id}/recycle-entry")
    public Response<CaseRecycleInfo> recycleEntry(@PathVariable long id) {
        return Response.builderSuccess(caseService.recycleEntry(id));
    }

    /** Restores a soft-deleted case via PUT /cases/{id}/restore and passes through optimistic lock error 40901. */
    @PutMapping("/{id}/restore")
    public Response<CaseInfo> restore(@PathVariable long id, @RequestBody RestoreCaseRequest request) {
        return Response.builderSuccess(caseService.restore(
                OperatorContext.currentOperatorId(), id, request.version()));
    }

 /** Permanently deletes recycle bin cases and cascades cleanup of associated rows. */
    @DeleteMapping("/{id}/purge")
    public Response<Long> purge(@PathVariable long id) {
        return Response.builderSuccess(caseService.purge(OperatorContext.currentOperatorId(), id));
    }

 /** Clears the recycle bin from the Clear button by deleting only soft-deleted rows in the current project and returning the number of cleared rows. */
    @DeleteMapping("/recycle-bin")
    public Response<Long> clearRecycleBin(@RequestParam long projectId) {
        return Response.builderSuccess(caseService.clearRecycleBin(
                OperatorContext.currentOperatorId(), projectId));
    }

    /**
     * Review action supports action=pass/need_change/reject with state machine and optimistic lock.
     * <p>Core throws 40901 version conflict or 40902 state machine interception, and GlobalExceptionHandler uniformly maps them to HTTP 409.
     */
    @PostMapping("/{id}/review")
    public Response<ReviewResult> review(@PathVariable long id, @RequestBody ReviewRequest request) {
        return Response.builderSuccess(caseService.review(
                OperatorContext.currentOperatorId(), id, ReviewAction.from(request.action()),
                request.remark(), request.version(), request.feedbackTypes()));
    }

    /**
     * A single assignment requires assigneeId and allows assignment only to cases in pending review or under review.
     * <p>Assignment permission is checked on the admin side for project owner and system administrator, while developer and regular member receive 403.
     */
    @PostMapping("/{id}/assign")
    public Response<AssignResult> assign(@PathVariable long id, @RequestBody AssignRequest request) {
        return Response.builderSuccess(caseService.assign(
                OperatorContext.currentOperatorId(), id, request.assigneeId()));
    }

    /**
     * Batch assignment is atomic, rolling back entirely if any item fails, and empty assigneeId triggers automatic load balancing.
     */
    @PostMapping("/batch-assign")
    public Response<AssignBatchResult> batchAssign(@RequestBody AssignBatchRequest request) {
        return Response.builderSuccess(caseService.assignBatch(OperatorContext.currentOperatorId(),
                request.projectId(), request.caseIds(), request.assigneeId()));
    }

    /**
     * Case automatic assignment supports preview and formal states, and empty caseIds means all unassigned pending-review cases in the project.
     * Dual gates for assignment eligibility are enforced on the admin side, while core focuses on candidate pool, greedy dispatch, and idempotent conditional updates.
     */
    @PostMapping("/auto-assign")
    public Response<com.sixbean.testpike.core.cases.api.dto.AutoAssignResultView> autoAssign(
            @RequestBody com.sixbean.testpike.core.cases.api.dto.AutoAssignRequest request) {
        return Response.builderSuccess(caseAutoAssignService.autoAssign(
                OperatorContext.currentOperatorId(), request));
    }

    /**
     * Batch review validates each item against the state machine, skips and lists conflicting items, and is non-atomic.
     * <p>Response data.succeeded/skipped; skip reasons are human-readable: terminal state, state already changed, not found, or cross-project H1.
     * Review permission is checked on the admin side for administrator, owner, and assignee, while core focuses on data correctness.
     */
    @PostMapping("/batch-review")
    public Response<ReviewBatchResult> batchReview(@RequestBody ReviewBatchRequest request) {
        return Response.builderSuccess(caseService.reviewBatch(
                OperatorContext.currentOperatorId(), request));
    }

    /**
 * Batch edit supports stage, priority, and tags, validates each item non-atomically, and reports success N and failure M.
 * <p>Edit permission is checked on the admin side for administrator and owner, while developer receives 403, and core checks H1 ownership.
     * Field validity is checked, and audit action=case_batch_edit is persisted by the service layer.
     */
    @PostMapping("/batch-edit")
    public Response<BatchEditResult> batchEdit(@RequestBody BatchEditRequest request) {
        return Response.builderSuccess(caseService.batchEdit(
                OperatorContext.currentOperatorId(), request));
    }
}
