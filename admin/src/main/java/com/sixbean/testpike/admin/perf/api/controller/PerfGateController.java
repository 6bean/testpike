package com.sixbean.testpike.admin.perf.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.perf.api.dto.PerfApprovalApplyRequest;
import com.sixbean.testpike.admin.perf.api.dto.PerfApprovalRejectRequest;
import com.sixbean.testpike.admin.perf.api.dto.PerfApproveRequest;
import com.sixbean.testpike.admin.perf.api.dto.PerfGateCheckRequest;
import com.sixbean.testpike.admin.perf.api.dto.PerfGateDecisionView;
import com.sixbean.testpike.admin.perf.api.dto.PerfRunPageView;
import com.sixbean.testpike.admin.perf.api.dto.PerfScenarioDraftRequest;
import com.sixbean.testpike.admin.perf.api.dto.PerfScenarioPageView;
import com.sixbean.testpike.admin.perf.api.dto.PerfScenarioSaveRequest;
import com.sixbean.testpike.admin.perf.api.dto.PerfTemplateDto;
import com.sixbean.testpike.admin.perf.api.dto.PerfWhitelistSaveRequest;
import com.sixbean.testpike.admin.perf.infrastructure.remote.client.CorePerfClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/api/v1/perf")
public class PerfGateController {

    private final CorePerfClient corePerfClient;
    private final ProjectAccessGuard guard;

    public PerfGateController(CorePerfClient corePerfClient, ProjectAccessGuard guard) {
        this.corePerfClient = corePerfClient;
        this.guard = guard;
    }

    
    @GetMapping("/whitelist")
    @RequirePermission("perm:project:project-view")
    public Response<List<Map<String, Object>>> listWhitelist(@RequestParam long projectId) {
        guard.assertCanManage(UserContext.current(), projectId);
        return Response.builderSuccess(corePerfClient.listWhitelist(projectId));
    }

    
    @PostMapping("/whitelist")
    @RequirePermission("perm:project:project-view")
    public Response<Long> createWhitelist(@RequestBody PerfWhitelistSaveRequest request) {
        guard.assertCanManage(UserContext.current(), request.projectId());
        return Response.builderSuccess(corePerfClient.createWhitelist(request));
    }

    
    @DeleteMapping("/whitelist/{id}")
    @RequirePermission("perm:project:project-view")
    public Response<Void> deleteWhitelist(@RequestParam long projectId, @PathVariable long id) {
        guard.assertCanManage(UserContext.current(), projectId);
        corePerfClient.deleteWhitelist(projectId, id);
        return Response.builderSuccess(null);
    }

    
    @GetMapping("/approvals")
    @RequirePermission("perm:project:project-view")
    public Response<List<Map<String, Object>>> listApprovals(@RequestParam long projectId) {
        guard.assertCanManage(UserContext.current(), projectId);
        return Response.builderSuccess(corePerfClient.listApprovals(projectId));
    }

    
    @PostMapping("/approvals")
    @RequirePermission("perm:project:project-view")
    public Response<Long> applyApproval(@RequestBody PerfApprovalApplyRequest request) {
        guard.assertCanManage(UserContext.current(), request.projectId());
        UserContext.currentUserId();
        return Response.builderSuccess(corePerfClient.applyApproval(request));
    }

    
    @PostMapping("/approvals/{id}/approve")
    @RequirePermission("perm:project:project-view")
    public Response<Void> approveApproval(@PathVariable long id, @RequestBody PerfApproveRequest request) {
        assertApprovalInProject(request.projectId(), id);
        long approverId = UserContext.currentUserId();
        corePerfClient.approveApproval(id, approverId);
        return Response.builderSuccess(null);
    }

    
    @PostMapping("/approvals/{id}/reject")
    @RequirePermission("perm:project:project-view")
    public Response<Void> rejectApproval(@PathVariable long id,
                                         @RequestBody PerfApprovalRejectRequest request) {
        assertApprovalInProject(request.projectId(), id);
        long approverId = UserContext.currentUserId();
        corePerfClient.rejectApproval(id, approverId, request.rejectReason());
        return Response.builderSuccess(null);
    }

    
    @PostMapping("/gate/precheck")
    @RequirePermission("perm:project:project-view")
    public Response<PerfGateDecisionView> precheck(@RequestBody PerfGateCheckRequest request) {
        guard.assertCanView(UserContext.current(), request.projectId());
        return Response.builderSuccess(corePerfClient.precheck(request));
    }

    
    @PostMapping("/runs/{runId}/stop")
    @RequirePermission("perm:project:project-view")
    public Response<Integer> stopRun(@RequestParam long projectId, @PathVariable long runId) {
        guard.assertCanManage(UserContext.current(), projectId);
        UserContext.currentUserId();
        return Response.builderSuccess(corePerfClient.stopRun(projectId, runId));
    }

    /**
     * Mark a finished perf run GOLDEN / CANDIDATE / VOID (B-7 golden-run marking).
     * The state and ownership gates sit in the core service (single source); the
     * one-golden-per-scenario demotion happens there atomically. Write gate =
     * project manager, login fail-fast.
     */
    @PostMapping("/runs/{runId}/mark")
    @RequirePermission("perm:project:project-view")
    public Response<Void> markRun(@RequestParam long projectId, @PathVariable long runId,
                                  @RequestParam String mark) {
        guard.assertCanManage(UserContext.current(), projectId);
        UserContext.currentUserId();
        corePerfClient.markRun(projectId, runId, mark);
        return Response.builderSuccess(null);
    }

    
    @PostMapping("/runs/stop-all")
    @RequirePermission("perm:project:project-view")
    public Response<Integer> stopAll(@RequestParam long projectId) {
        guard.assertCanManage(UserContext.current(), projectId);
        UserContext.currentUserId();
        return Response.builderSuccess(corePerfClient.stopAllRuns(projectId));
    }

    
    @PostMapping("/runs/{runId}/report-card")
    @RequirePermission("perm:project:project-view")
    public Response<Integer> sendReportCard(@RequestParam long projectId, @PathVariable long runId) {
        guard.assertCanManage(UserContext.current(), projectId);
        UserContext.currentUserId();
        return Response.builderSuccess(corePerfClient.sendReportCard(projectId, runId));
    }

    
    private void assertApprovalInProject(long projectId, long approvalId) {
        guard.assertCanManage(UserContext.current(), projectId);
        boolean belongs = corePerfClient.listApprovals(projectId).stream()
                .anyMatch(row -> ((Number) row.get("id")).longValue() == approvalId);
        if (!belongs) {
            throw new ServiceException("该压测申请单不存在或不属于当前项目，请刷新列表后重试",
                    BizErrorCode.NOT_FOUND);
        }
    }


    
    @GetMapping("/scenarios")
    @RequirePermission("perm:project:project-view")
    public Response<List<Map<String, Object>>> listScenarios(@RequestParam long projectId) {
        guard.assertCanManage(UserContext.current(), projectId);
        return Response.builderSuccess(corePerfClient.listScenarios(projectId));
    }

    /**
 * One paged window of the project's scenarios display-scale
 * iron law: the scenario ledger page consumes this door). Clamp, the
 * whitelisted sort order and the truthful total are core semantics; the
 * read key opens both the full list and the window (zero new permission
 * keys, same guard posture as the unbounded list).
 */
    @GetMapping("/scenarios/page")
    @RequirePermission("perm:project:project-view")
    public Response<PerfScenarioPageView> pageScenarios(@RequestParam long projectId,
                                                         @RequestParam(defaultValue = "1") int page,
                                                         @RequestParam(defaultValue = "20") int pageSize,
                                                         @RequestParam(required = false) String sortBy,
                                                         @RequestParam(required = false) String sortDir,
                                                         @RequestParam(defaultValue = "0") long programId) {
        // Program lens : lens-without-project skips the single-project gate —
        // an unreachable lens is an honest empty page, never a cross-project dump.
        guard.assertCanManageWithinLens(UserContext.current(), projectId, programId);
        return Response.builderSuccess(
                corePerfClient.pageScenarios(projectId, page, pageSize, sortBy, sortDir, programId));
    }

    /**
     * Shipped scenario-template catalogue (GET /api/v1/perf/templates) — CF-4
     * wizard picker data. Read-only and project-independent: templates carry
     * {{ENV:}} placeholders and example paths only, never credentials, so no
     * project ownership applies (same posture as the signature auth presets).
     * Catalogue judgement (prefix filter + honest drop + save-gate-domain
     * pre-check) lives in core PerfScenarioTemplateCatalog; admin bridges
     * verbatim under the read key.
     */
    @GetMapping("/templates")
    @RequirePermission("perm:project:project-view")
    public Response<List<PerfTemplateDto>> listTemplates() {
        return Response.builderSuccess(corePerfClient.listTemplates());
    }

    /**
     * Engine catalogue for the wizard engine option (GET /api/v1/perf/engines).
     * Read-only and project-independent, same posture as the template
     * catalogue above: the declared-engine set and each engine's rate-caliber
     * caveat are judged solely in core (dispatch value domain + disclosure
     * component), admin bridges verbatim under the project read key and owns
     * no filtering, re-ordering or re-wording.
     */
    @GetMapping("/engines")
    @RequirePermission("perm:project:project-view")
    public Response<List<Map<String, Object>>> listEngines() {
        return Response.builderSuccess(corePerfClient.engines());
    }

    /**
     * Resource pre-flight readout (GET /api/v1/perf/resource-preflight) — the
     * CF-5/CF-7 advance notice in front of the launch button: executor pool /
     * capability / queue outlook plus dataset demand vs capacity for one
     * scenario's next launch. Read-only wording (null fields = honest no-claim,
     * the card stays silent); every judgement is core's single source and the
     * launch gates own rejection — this door blocks nothing.
     */
    @GetMapping("/resource-preflight")
    @RequirePermission("perm:project:project-view")
    public Response<Map<String, Object>> resourcePreflight(@RequestParam long projectId,
                                                            @RequestParam long scenarioId) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(corePerfClient.resourcePreflight(projectId, scenarioId));
    }

    
    @GetMapping("/scenarios/{id}")
    @RequirePermission("perm:project:project-view")
    public Response<Map<String, Object>> viewScenario(@RequestParam long projectId,
                                                      @PathVariable long id) {
        guard.assertCanManage(UserContext.current(), projectId);
        return Response.builderSuccess(corePerfClient.viewScenario(projectId, id));
    }

    
    @PostMapping("/scenarios")
    @RequirePermission("perm:project:project-view")
    public Response<Long> createScenario(@RequestBody PerfScenarioSaveRequest request) {
        guard.assertCanManage(UserContext.current(), request.projectId());
        return Response.builderSuccess(corePerfClient.createScenario(request));
    }

    /**
 * AI scenario draft for wizard prefill (B-11 . Drafting burns model
 * tokens and prefills a project scenario asset, so the write-asset management
 * guard applies (same gate as scenario create); switch refusal and step
 * trimming wording ride through untranslated from core (no second validation
 * here, red line 6). Nothing is persisted — saving stays behind the human-
 * confirmed create/update doors.
 */
    @PostMapping("/scenarios/draft")
    @RequirePermission("perm:project:project-view")
    public Response<Map<String, Object>> draftScenario(@RequestBody PerfScenarioDraftRequest request) {
        UserContext.currentUserId(); // fail fast with 401 when logged out — anonymous callers must never spend model tokens
        guard.assertCanManage(UserContext.current(), request.projectId());
        return Response.builderSuccess(
                corePerfClient.draftScenario(request.projectId(), request.description()));
    }

    
    @PutMapping("/scenarios/{id}")
    @RequirePermission("perm:project:project-view")
    public Response<Void> updateScenario(@RequestParam long projectId, @PathVariable long id,
                                         @RequestBody PerfScenarioSaveRequest request) {
        guard.assertCanManage(UserContext.current(), projectId);
        corePerfClient.updateScenario(projectId, id, request);
        return Response.builderSuccess(null);
    }

    /**
     * Delete-impact ledger (A-13): same management gate as the scenario write
     * doors; every count (in-flight runs, finished runs kept, approval links)
     * is decided in core — the bridge only composes the call (no re-checks).
     */
    @GetMapping("/scenarios/{id}/delete-impact")
    @RequirePermission("perm:project:project-view")
    public Response<Map<String, Object>> scenarioDeleteImpact(@RequestParam long projectId,
                                                              @PathVariable long id) {
        UserContext.currentUserId(); // fail-fast: anonymous never reaches core
        guard.assertCanManage(UserContext.current(), projectId);
        return Response.builderSuccess(corePerfClient.scenarioDeleteImpact(projectId, id));
    }

    /**
     * Delete a scenario (A-13, D2 ruling): finished runs survive decoupled —
     * core owns the refusal (in-flight run) and the ledger rewrite; the human
     * wording rides through untranslated (red line 6, no second validation).
     */
    @DeleteMapping("/scenarios/{id}")
    @RequirePermission("perm:project:project-view")
    public Response<Void> deleteScenario(@RequestParam long projectId, @PathVariable long id) {
        guard.assertCanManage(UserContext.current(), projectId);
        UserContext.currentUserId(); // fail-fast, same posture as the dataset delete door
        corePerfClient.deleteScenario(projectId, id);
        return Response.builderSuccess(null);
    }

    /**
     * Launch a perf run (three-layer gate + precheck single-point in the core launch
     * chain; operator = login context via X-Operator-Id). GB-1: the optional envId
     * overrides the scenario-bound environment for this run (absent = scenario
     * default; cross-project/unknown ids are refused by core with a human reason).
     */
    @PostMapping("/scenarios/{id}/runs")
    @RequirePermission("perm:project:project-view")
    public Response<Long> launchRun(@RequestParam long projectId, @PathVariable long id,
                                    @RequestParam(required = false) Long envId) {
        guard.assertCanManage(UserContext.current(), projectId);
        UserContext.currentUserId();
        return Response.builderSuccess(corePerfClient.launchRun(projectId, id, envId));
    }

    
    @GetMapping("/runs")
    @RequirePermission("perm:project:project-view")
    public Response<List<Map<String, Object>>> listRuns(@RequestParam long projectId,
                                                        @RequestParam long scenarioId) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(corePerfClient.listRuns(projectId, scenarioId));
    }

    /**
 * One paged window of a scenario's runs the run drawer
 * consumes this door; read door = same member-visible guard as the plain
 * list, zero new permission keys; clamp/total truth stay core semantics
 * and the bridge never re-paginates).
 */
    @GetMapping("/runs/page")
    @RequirePermission("perm:project:project-view")
    public Response<PerfRunPageView> pageRuns(@RequestParam long projectId,
                                              @RequestParam long scenarioId,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "20") int pageSize) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(corePerfClient.pageRuns(projectId, scenarioId,
                page, pageSize));
    }

    /**
 * Project-wide run ledger window : the ledger page lists every
 * run of the project segment, finished history whose scenario was deleted
 * included. Read door = the same member-visible guard as the drawer
 * window, zero new permission keys; the bridge stays thin.
 */
    @GetMapping("/runs/project-page")
    @RequirePermission("perm:project:project-view")
    public Response<PerfRunPageView> pageProjectRuns(@RequestParam long projectId,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "20") int pageSize,
                                                     @RequestParam(required = false) String sortBy,
                                                     @RequestParam(required = false) String sortDir,
                                                     @RequestParam(required = false) Integer status,
                                                     @RequestParam(required = false) String mark) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(corePerfClient.pageProjectRuns(projectId,
                page, pageSize, sortBy, sortDir, status, mark));
    }

    
    @GetMapping("/runs/{runId}")
    @RequirePermission("perm:project:project-view")
    public Response<Map<String, Object>> viewRun(@RequestParam long projectId,
                                                 @PathVariable long runId) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(corePerfClient.viewRun(projectId, runId));
    }

    
    @GetMapping("/runs/{runId}/report")
    @RequirePermission("perm:project:project-view")
    public Response<Map<String, Object>> buildReport(@RequestParam long projectId,
                                                     @PathVariable long runId) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(corePerfClient.buildReport(projectId, runId));
    }

    /**
 * Run-report export : download the same single-source
 * report as pdf/html. Read gate matches the online report door (view guard
 * + project-view permission — export serializes what the page shows, so it
 * never needs a wider door). Filename names the run so the saved file is
 * self-identifying; refusals ride core wording.
 */
    @GetMapping("/runs/{runId}/report-export")
    @RequirePermission("perm:project:project-view")
    public ResponseEntity<byte[]> exportReport(@RequestParam long projectId,
                                               @PathVariable long runId,
                                               @RequestParam String format) {
        guard.assertCanView(UserContext.current(), projectId);
        byte[] data = corePerfClient.exportReport(projectId, runId, format);
        boolean pdf = "pdf".equalsIgnoreCase(format == null ? "" : format.trim());
        String fileName = java.net.URLEncoder.encode("perf-run-" + runId + (pdf ? ".pdf" : ".html"),
                java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_TYPE,
                        pdf ? "application/pdf" : "text/html;charset=UTF-8")
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + fileName)
                .body(data);
    }

    /**
 * AI run-insight read-out (B-11 . Read face of one finished run —
 * same view guard as the report it annotates — but it spends model tokens,
 * so the logged-in fail-fast applies (anonymous callers must never burn
 * budget, same posture as the drafting door). Switch/ownership/pending
 * refusal wording rides through untranslated from core (red line 6).
 */
    @PostMapping("/runs/{runId}/insight")
    @RequirePermission("perm:project:project-view")
    public Response<Map<String, Object>> runInsight(@RequestParam long projectId,
                                                    @PathVariable long runId) {
        guard.assertCanView(UserContext.current(), projectId);
        UserContext.currentUserId(); // fail fast with 401 when logged out — anonymous callers must never spend model tokens
        return Response.builderSuccess(corePerfClient.runInsight(projectId, runId));
    }

    
    @GetMapping("/runs/compare")
    @RequirePermission("perm:project:project-view")
    public Response<Map<String, Object>> compareReports(@RequestParam long projectId,
                                                        @RequestParam long runA,
                                                        @RequestParam long runB) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(corePerfClient.compareReports(projectId, runA, runB));
    }

    /**
 * Scenario trend rows : ascending terminal-run rows with
 * whole-run percentile recomputation; window/ownership semantics live in core,
 * this read face rides the same guard posture as report/compare (zero new
 * permission keys).
 */
    @GetMapping("/scenarios/{id}/trend")
    @RequirePermission("perm:project:project-view")
    public Response<List<Map<String, Object>>> scenarioTrend(@RequestParam long projectId,
                                                             @PathVariable long id) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(corePerfClient.scenarioTrend(projectId, id));
    }

    
    @GetMapping("/runs/{runId}/frames")
    @RequirePermission("perm:project:project-view")
    public Response<List<Map<String, Object>>> listFrames(@RequestParam long projectId,
                                                          @PathVariable long runId) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(corePerfClient.listFrames(projectId, runId));
    }

    /**
 * Live-curve incremental face : the running page polls this with the last
 * second it saw (sinceOffset) and appends; oversized windows arrive bucketed with an
 * honest sampled flag. Same view guard as frames — the cursor is a read optimization,
 * never an authorization shortcut.
 */
    @GetMapping("/runs/{runId}/live-curve")
    @RequirePermission("perm:project:project-view")
    public Response<Map<String, Object>> liveCurve(@RequestParam long projectId,
                                                   @PathVariable long runId,
                                                   @RequestParam(required = false) Integer sinceOffset) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(corePerfClient.liveCurve(projectId, runId, sinceOffset));
    }

    
    @GetMapping("/runs/{runId}/events")
    @RequirePermission("perm:project:project-view")
    public Response<List<Map<String, Object>>> listEvents(@RequestParam long projectId,
                                                          @PathVariable long runId) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(corePerfClient.listEvents(projectId, runId));
    }

    /** Error-class TopN of the run (B-6 error drawer read face; same view guard as frames). */
    @GetMapping("/runs/{runId}/error-topn")
    @RequirePermission("perm:project:project-view")
    public Response<List<Map<String, Object>>> errorClassTopN(@RequestParam long projectId,
                                                              @PathVariable long runId) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(corePerfClient.errorClassTopN(projectId, runId));
    }
}
