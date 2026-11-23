package com.sixbean.testpike.admin.spec.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.spec.api.dto.InventoryCoverageSummary;
import com.sixbean.testpike.admin.spec.api.dto.InventoryEndpointView;
import com.sixbean.testpike.admin.spec.infrastructure.remote.client.CoreApiInventoryClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API inventory (contract endpoint list) user-facing doors.
 * <p>Two-layer permission, mirrored from the mock inventory page precedent:
 * the functional key perm:apitest:inventory:read (catalog; VIEW family so all
 * member roles carry it) plus the H1 project guard — the page shows one
 * project segment, never a cross-project scan. Anchor-version selection, asset
 * counts and coverage math are core single-source; this controller only
 * receives parameters, checks scope and wraps the response (thin controller).
 * <p>The inventory read key is spelled perm:apitest:inventory:read rather than
 * the design-doc literal perm:api-inventory:read: the catalog key-format gate
 * (perm:{domain}:{action}, domain segment without punctuation) rejects the
 * literal, same correction path as the historical perm:comment → perm:comment:use
 * ruling. The three-segment family follows the frozen appauto resource:action
 * precedent.
 */
@RestController
@RequestMapping("/api/v1/api-inventory")
public class ApiInventoryController {

    private final CoreApiInventoryClient coreApiInventoryClient;
    private final ProjectAccessGuard guard;

    public ApiInventoryController(CoreApiInventoryClient coreApiInventoryClient,
                                  ProjectAccessGuard guard) {
        this.coreApiInventoryClient = coreApiInventoryClient;
        this.guard = guard;
    }

    /** Paged endpoint rows of the anchored contract version (members only, H1).
 * uncoveredOnly=true narrows the page to the working list (endpoints still
 * without a request) — the segment is a core judgment, forwarded verbatim. */
    @GetMapping("/endpoints")
    @RequirePermission("perm:apitest:inventory:read")
    public Response<PageView<InventoryEndpointView>> endpoints(
            @RequestParam long projectId,
            @RequestParam String specName,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "false") boolean uncoveredOnly) {
        CurrentUser user = UserContext.current();
        guard.assertCanView(user, projectId);
        return Response.builderSuccess(
                coreApiInventoryClient.endpoints(projectId, specName, page, pageSize,
                        uncoveredOnly));
    }

    /** Coverage summary card (denominator = latest non-deprecated rows, H1). */
    @GetMapping("/coverage")
    @RequirePermission("perm:apitest:inventory:read")
    public Response<InventoryCoverageSummary> coverage(@RequestParam long projectId,
                                                       @RequestParam String specName) {
        CurrentUser user = UserContext.current();
        guard.assertCanView(user, projectId);
        return Response.builderSuccess(
                coreApiInventoryClient.coverage(projectId, specName));
    }
}
