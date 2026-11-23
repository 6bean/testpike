package com.sixbean.testpike.admin.spec.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.spec.api.dto.ApiSpecChangeAnalysisView;
import com.sixbean.testpike.admin.spec.api.dto.SpecChangeHandleRequest;
import com.sixbean.testpike.admin.spec.api.dto.SpecChangeReanalyzeRequest;
import com.sixbean.testpike.admin.spec.infrastructure.remote.client.CoreApiSpecClient;
import com.sixbean.testpike.admin.spec.infrastructure.remote.client.CoreSpecChangeAnalysisClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Contract change-analysis review queue (the API-test "change analysis" review page's
 * only data source). Permission posture mirrors the F3 document-change precedent:
 * list = project member (assertCanView, H1); reanalyze/disposition = the
 * requirement-write face of the ROW's owning project (assertCanWriteRequirement,
 * permission source = row ownership fetched from core, never a client-supplied
 * projectId — so a crafted body cannot disposition another project's rows).
 * Batch disposition resolves every checked row's project and requires the write
 * face of all of them (one foreign row rejects the call).
 * Queue state rules (duplicate disposition, all-or-nothing batch) live in core
 * only — the bridge does not re-validate (no second rule source).
 */
@RestController
@RequestMapping("/api/v1/api-spec-change-analyses")
public class ApiSpecChangeAnalysisController {

    private final CoreSpecChangeAnalysisClient analysisClient;
    private final CoreApiSpecClient coreApiSpecClient;
    private final ProjectAccessGuard guard;

    public ApiSpecChangeAnalysisController(CoreSpecChangeAnalysisClient analysisClient,
                                           CoreApiSpecClient coreApiSpecClient,
                                           ProjectAccessGuard guard) {
        this.analysisClient = analysisClient;
        this.coreApiSpecClient = coreApiSpecClient;
        this.guard = guard;
    }

    /** Review-queue page (project members, H1; status null = all, 0 = pending, 1 = handled; id DESC). */
    @GetMapping
    public Response<PageView<ApiSpecChangeAnalysisView>> list(@RequestParam long projectId,
                                                              @RequestParam(required = false) Integer status,
                                                              @RequestParam(defaultValue = "1") int page,
                                                              @RequestParam(defaultValue = "20") int size) {
        CurrentUser user = UserContext.current();
        guard.assertCanView(user, projectId);
        return Response.builderSuccess(analysisClient.list(projectId, status, page, size));
    }

    /** Queue row detail (row fetched first, then gated by the row's owning project). */
    @GetMapping("/{id}")
    public Response<ApiSpecChangeAnalysisView> get(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        ApiSpecChangeAnalysisView row = analysisClient.get(id);
        guard.assertCanView(user, row.projectId());
        return Response.builderSuccess(row);
    }

    /** Queue row of a contract version (badge deep link; gated by the row's project). */
    @GetMapping("/by-spec/{newSpecId}")
    public Response<ApiSpecChangeAnalysisView> getBySpec(@PathVariable long newSpecId) {
        CurrentUser user = UserContext.current();
        ApiSpecChangeAnalysisView row = analysisClient.getBySpec(newSpecId);
        guard.assertCanView(user, row.projectId());
        return Response.builderSuccess(row);
    }

    /** Explicit re-analysis (subscription poll / ops retry; write gate = requirement
     *  face of the version's owning project resolved from the spec row, never a body param). */
    @PostMapping("/reanalyze")
    public Response<ApiSpecChangeAnalysisView> reanalyze(
            @RequestBody SpecChangeReanalyzeRequest request) {
        CurrentUser user = UserContext.current();
        guard.assertCanWriteRequirement(user, specProjectIdOf(request.newSpecId()));
        return Response.builderSuccess(
                analysisClient.reanalyze(request.newSpecId(), request.detectedSource()));
    }

    /** Disposition trace (pending → handled, this row only; no request/case state
     *  machine linkage — those rules live in core, single source). */
    @PostMapping("/{id}/handle")
    public Response<ApiSpecChangeAnalysisView> handle(@PathVariable long id,
                                                      @RequestBody(required = false) SpecChangeHandleRequest request) {
        CurrentUser user = UserContext.current();
        guard.assertCanWriteRequirement(user, projectIdOf(id));
        return Response.builderSuccess(analysisClient.handle(id,
                request == null ? null : request.note()));
    }

    /** Batch disposition of checked rows (one trace for all; any row without the
     *  write face on its owning project rejects the whole call before anything lands). */
    @PostMapping("/handle-batch")
    public Response<List<ApiSpecChangeAnalysisView>> handleBatch(
            @RequestBody SpecChangeHandleRequest request) {
        CurrentUser user = UserContext.current();
        Set<Long> projects = new LinkedHashSet<>();
        for (Long id : request.ids() == null ? List.<Long>of() : request.ids()) {
            projects.add(projectIdOf(id));
        }
        for (Long projectId : projects) {
            guard.assertCanWriteRequirement(user, projectId);
        }
        return Response.builderSuccess(analysisClient.handleBatch(request.ids(), request.note()));
    }

    /** Disposition permission source = the row's owning project (core 404 passes
     *  through untouched — no swallowing, no fabricated project). */
    private long projectIdOf(long id) {
        return analysisClient.get(id).projectId();
    }

    /** Version → owning project via the spec row itself (an un-analyzed version has
     *  no queue row; core reanalyze then gives the plain-language rejection). */
    private long specProjectIdOf(long newSpecId) {
        return coreApiSpecClient.get(newSpecId).projectId();
    }
}
