package com.sixbean.testpike.admin.webelement.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.webelement.infrastructure.remote.client.CorePageWriteProfileClient;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.webelement.PageWriteProfilePageView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Page write-risk ledger screen door : lists the pages the
 * survey guard booked as firing write-type requests outside their load
 * baseline, so a reviewer can see which pages autosave without re-running a
 * probe. Reads need project membership, re-checked server-side against the
 * requested project segment; the ledger itself is observation-only and has no
 * user-facing write door by design (tightening the guard is a probe concern,
 * not a row to edit).
 */
@RestController
@RequestMapping("/api/v1/page-write-profiles")
public class PageWriteProfileController {

    private final CorePageWriteProfileClient client;
    private final ProjectAccessGuard guard;

    public PageWriteProfileController(CorePageWriteProfileClient client, ProjectAccessGuard guard) {
        this.client = client;
        this.guard = guard;
    }

    /** Risk pages of the current project segment, newest activity first (paged, S-group contract). */
    @GetMapping
    @RequirePermission("perm:project:project-view")
    public Response<PageWriteProfilePageView> page(@RequestParam long projectId,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "50") int pageSize,
                                                   @RequestParam(required = false) String keyword) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(client.pageProfiles(projectId, page, pageSize, keyword));
    }

    /**
     * Convert one booked failure of a risk row into a pending case draft.
     * The screen door adds only the membership gate (same posture as the
     * read door); what the row contains and whether the index is real is
     * refused by core's single converter, and nothing is written until a
     * human confirms the filed card through the generic confirmation door.
     */
    @PostMapping("/case-draft")
    @RequirePermission("perm:project:project-view")
    public Response<Long> caseDraft(@RequestParam long projectId,
                                    @RequestParam long profileId,
                                    @RequestParam int failureIndex) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(client.caseDraftFromFailure(projectId, profileId, failureIndex));
    }
}
