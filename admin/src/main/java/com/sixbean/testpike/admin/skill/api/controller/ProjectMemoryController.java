package com.sixbean.testpike.admin.skill.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.skill.api.dto.ProjectMemoryCreateRequest;
import com.sixbean.testpike.admin.skill.api.dto.ProjectMemoryView;
import com.sixbean.testpike.admin.skill.app.component.SkillAccessGuard;
import com.sixbean.testpike.admin.skill.infrastructure.remote.client.ProjectMemoryClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Project-memory ledger admin door ; 02 §2.16
 * /api/v1/project-memory six routes → core /internal/project-memory).
 * <p>Thin controller — permission posture: read routes mount
 * perm:aichat:project-memory (VIEW family = every member holds it, the
 * zero-drift mount precedent from the skills domain S3) plus the H1 member
 * guard {@code assertCanView}; write routes reuse
 * {@link SkillAccessGuard#assertCanManage} (ADMIN exempt from the lead rule,
 * a refusal is a 403 that never reaches core) — no second guard is born here.
 * The state machine, audit trail and every stale/cross-project human message
 * live single-source in core ProjectMemoryLedgerService; payloads travel as
 * whole objects (bridge-layer iron rule, no field-by-field copying).
 */
@RestController
@RequestMapping("/api/v1/project-memory")
public class ProjectMemoryController {

    private final ProjectMemoryClient memoryClient;
    private final ProjectAccessGuard projectAccessGuard;
    private final SkillAccessGuard skillAccessGuard;

    public ProjectMemoryController(ProjectMemoryClient memoryClient,
                                   ProjectAccessGuard projectAccessGuard,
                                   SkillAccessGuard skillAccessGuard) {
        this.memoryClient = memoryClient;
        this.projectAccessGuard = projectAccessGuard;
        this.skillAccessGuard = skillAccessGuard;
    }

    /** Ledger page rows (content + traceability anchors + injection state + hit count) with keyword/state filters and paging. */
    @GetMapping
    @RequirePermission("perm:aichat:project-memory")
    public Response<PageView<ProjectMemoryView>> list(@RequestParam Long projectId,
                                                      @RequestParam(required = false) String keyword,
                                                      @RequestParam(required = false) Integer injectStatus,
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        projectAccessGuard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(memoryClient.list(projectId, keyword, injectStatus, page, size));
    }

    /** Manual fixed-injection entry (lands effective — it is a human-written fact; blank content / unknown project refusals are core single-source). */
    @PostMapping
    public Response<ProjectMemoryView> create(@RequestBody ProjectMemoryCreateRequest request) {
        skillAccessGuard.assertCanManage(UserContext.current(), request.projectId());
        return Response.builderSuccess(memoryClient.create(request));
    }

    /** Enable/disable (a disabled entry is absent from the next assembly through the same consumption door — no second injection chain). */
    @PutMapping("/{id}/status")
    public Response<ProjectMemoryView> setStatus(@PathVariable long id,
                                                 @RequestParam Long projectId,
                                                 @RequestParam int status) {
        skillAccessGuard.assertCanManage(UserContext.current(), projectId);
        return Response.builderSuccess(memoryClient.setInjectStatus(id, projectId, status));
    }

    /** Reject a distilled entry awaiting confirmation (pending 2 -> rejected 3, audited core-side; never injected, stays visible in the ledger). */
    @PostMapping("/{id}/reject")
    public Response<ProjectMemoryView> reject(@PathVariable long id, @RequestParam Long projectId) {
        skillAccessGuard.assertCanManage(UserContext.current(), projectId);
        return Response.builderSuccess(memoryClient.reject(id, projectId));
    }

    /** Soft-delete into the recycle bin (SkillRecycleDrawer-shaped exit; stale-conflict messages are core single-source). */
    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable long id, @RequestParam Long projectId) {
        skillAccessGuard.assertCanManage(UserContext.current(), projectId);
        memoryClient.delete(id, projectId);
        return Response.builderSuccess();
    }

    /** Recycle bin page (deleted=1, project-scoped, paged). */
    @GetMapping("/recycle-bin")
    @RequirePermission("perm:aichat:project-memory")
    public Response<PageView<ProjectMemoryView>> recycleBin(@RequestParam Long projectId,
                                                            @RequestParam(defaultValue = "1") int page,
                                                            @RequestParam(defaultValue = "20") int size) {
        projectAccessGuard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(memoryClient.recycleBin(projectId, page, size));
    }

    /** Restore from the recycle bin (previous injection state returns; cross-project ids are rejected by the core segment). */
    @PostMapping("/{id}/restore")
    public Response<ProjectMemoryView> restore(@PathVariable long id, @RequestParam Long projectId) {
        skillAccessGuard.assertCanManage(UserContext.current(), projectId);
        return Response.builderSuccess(memoryClient.restore(id, projectId));
    }
}
