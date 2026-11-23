package com.sixbean.testpike.admin.perf.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.perf.api.dto.PerfDatasetPageView;
import com.sixbean.testpike.admin.perf.infrastructure.remote.client.CorePerfDatasetClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * User-facing doors for the perf CSV dataset ledger (api/v1/perf/datasets —
 * . The wizard's data-pool step and the ledger page consume
 * these four endpoints.
 * <p>Permissions: every door is project-manager gated (assertCanManage — a
 * dataset is a project-level asset feeding launches; read doors carry the
 * same key as the scenario ledger, zero new permission keys). All validation
 * (RFC4180 parse, column names, K8 row budget, cross-project ownership) lives
 * in the core service exactly once — admin only authorizes and forwards
 * (red line 6), core's human-language errors surface verbatim.
 */
@RestController
@RequestMapping("/api/v1/perf/datasets")
public class PerfDatasetController {

    private final CorePerfDatasetClient datasetClient;
    private final ProjectAccessGuard guard;

    public PerfDatasetController(CorePerfDatasetClient datasetClient, ProjectAccessGuard guard) {
        this.datasetClient = datasetClient;
        this.guard = guard;
    }

    /** Upload one CSV file into the project's dataset ledger (multipart: file + projectId + name). */
    @PostMapping
    @RequirePermission("perm:project:project-view")
    public Response<Long> upload(@RequestParam long projectId,
                                 @RequestParam String name,
                                 @RequestPart("file") MultipartFile file) throws IOException {
        guard.assertCanManage(UserContext.current(), projectId);
        UserContext.currentUserId(); // fail-fast: anonymous uploads never reach core
        return Response.builderSuccess(datasetClient.upload(projectId, name,
                file.getOriginalFilename(), file.getBytes()));
    }

    /**
     * Paged dataset window of the project (display-scale iron law: the ledger
     * page never pulls an unbounded list). Clamp, whitelisted order and the
     * truthful total are core semantics; the bridge passes them through.
     */
    @GetMapping("/page")
    @RequirePermission("perm:project:project-view")
    public Response<PerfDatasetPageView> page(@RequestParam long projectId,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "20") int pageSize,
                                              @RequestParam(required = false) String sortBy,
                                              @RequestParam(required = false) String sortDir,
                                              @RequestParam(defaultValue = "0") long programId) {
        // Program lens : lens-without-project skips the single-project gate —
        // the shared gate encodes exactly that rule (core narrows lens ∩ visibility).
        guard.assertCanManageWithinLens(UserContext.current(), projectId, programId);
        return Response.builderSuccess(
                datasetClient.listPaged(projectId, page, pageSize, sortBy, sortDir, programId));
    }

    /** Dataset detail + first N preview rows streamed from object storage. */
    @GetMapping("/{id}")
    @RequirePermission("perm:project:project-view")
    public Response<Map<String, Object>> view(@RequestParam long projectId, @PathVariable long id) {
        guard.assertCanManage(UserContext.current(), projectId);
        return Response.builderSuccess(datasetClient.view(projectId, id));
    }

    /**
 * Engine × strategy matrix for the wizard's honest-disable UI. Global
 * semantics (no project segment), so the door only needs the page-view
 * permission — the project-level gate would reject the very users the
 * matrix is meant to warn.
 */
    @GetMapping("/alloc-policy")
    @RequirePermission("perm:project:project-view")
    public Response<Map<String, Object>> allocPolicy() {
        UserContext.currentUserId(); // fail-fast: anonymous never reaches core
        return Response.builderSuccess(datasetClient.allocPolicy());
    }

    /** Delete a dataset (ledger row + stored object; cross-project id refused by core). */
    @DeleteMapping("/{id}")
    @RequirePermission("perm:project:project-view")
    public Response<Void> delete(@RequestParam long projectId, @PathVariable long id) {
        guard.assertCanManage(UserContext.current(), projectId);
        UserContext.currentUserId(); // fail-fast, same posture as the kill-switch doors
        datasetClient.delete(projectId, id);
        return Response.builderSuccess(null);
    }
}
