package com.sixbean.testpike.admin.linkage.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.common.linkage.LinkagePendingImpacts;
import com.sixbean.testpike.admin.linkage.infrastructure.remote.client.CoreLinkageImpactClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pending downstream-impact ledger for the user-facing card panel and the
 * stale marks . Permission posture follows the
 * record-draft bridge precedent: the owning project is whatever core reads
 * from the upstream object itself — a caller-supplied projectId only names
 * the viewer's segment and can never widen it. An empty account has no owner
 * to disclose and answers empty without a segment (zero-impact saves stay
 * zero-disturbance by construction); a non-empty account must be read from
 * inside its owning project's page.
 */
@RestController
@RequestMapping("/api/v1/linkage-impacts")
public class LinkagePendingImpactController {

    private final CoreLinkageImpactClient client;
    private final ProjectAccessGuard guard;

    public LinkagePendingImpactController(CoreLinkageImpactClient client,
                                          ProjectAccessGuard guard) {
        this.client = client;
        this.guard = guard;
    }

    /** Pending impacts of one saved object, oldest first; projectId = viewer's segment. */
    @GetMapping("/pending")
    @RequirePermission("perm:project:project-view")
    public Response<LinkagePendingImpacts> pending(@RequestParam String upstreamType,
                                                       @RequestParam long upstreamId,
                                                       @RequestParam(required = false) Long projectId) {
        LinkagePendingImpacts ledger = client.pending(upstreamType, upstreamId);
        if (ledger.projectId() == null) {
            return Response.builderSuccess(ledger);
        }
        if (projectId == null) {
            throw new ServiceException("请先在顶栏选择所属项目，再查看该对象的下游影响账",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (!projectId.equals(ledger.projectId())) {
            throw new ServiceException("该下游影响账属于项目 " + ledger.projectId()
                    + "，与当前选择的项目不一致，请切换项目后查看", BizErrorCode.FORBIDDEN);
        }
        CurrentUser user = UserContext.current();
        guard.assertCanView(user, ledger.projectId());
        return Response.builderSuccess(ledger);
    }

    /**
 * Live ledger anchors for the draft cards of one grouped card (
 * segment D③ frontend read). Permission posture is the same as /pending:
 * the owners are the draft rows' own project ids resolved by core, the
 * caller's projectId only names the viewer's segment; any foreign draft
 * in the batch rejects the whole call (never a silently filtered
 * list — partial answers would let a group leak other projects).
 */
    @GetMapping("/by-drafts")
    @RequirePermission("perm:project:project-view")
    public Response<java.util.List<com.sixbean.testpike.common.linkage.LinkageDraftImpact>> byDrafts(
            @RequestParam java.util.List<Long> draftIds,
            @RequestParam(required = false) Long projectId) {
        java.util.List<com.sixbean.testpike.common.linkage.LinkageDraftImpact> anchors =
                client.byDrafts(draftIds);
        if (anchors.isEmpty()) {
            return Response.builderSuccess(anchors);
        }
        if (projectId == null) {
            throw new ServiceException("请先在顶栏选择所属项目，再查看这些草稿的下游影响账",
                    BizErrorCode.VALIDATION_ERROR);
        }
        boolean foreign = anchors.stream()
                .anyMatch(a -> a.projectId() == null || !projectId.equals(a.projectId()));
        if (foreign) {
            throw new ServiceException("这批草稿不属于当前选择的项目，请切换项目后查看",
                    BizErrorCode.FORBIDDEN);
        }
        CurrentUser user = UserContext.current();
        guard.assertCanView(user, projectId);
        return Response.builderSuccess(anchors);
    }
}
