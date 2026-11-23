package com.sixbean.testpike.admin.webelement.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.webelement.api.dto.ReferencingCasePage;
import com.sixbean.testpike.admin.webelement.infrastructure.remote.client.CoreWebElementClient;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reference list door for the element drawer (MCP tail package 1): the
 * cases whose step anchor evidence names this element, read straight from
 * the anchor ledger — no second index, no ledger-side counters. Membership
 * is guarded by the page's own owning project resolved server-side; an
 * unknown element answers an empty page and burns no guard call.
 */
@RestController
public class WebElementReferencingCasesController {

    private final CoreWebElementClient client;
    private final ProjectAccessGuard guard;

    public WebElementReferencingCasesController(CoreWebElementClient client,
                                                ProjectAccessGuard guard) {
        this.client = client;
        this.guard = guard;
    }

    /** Bounded referencing-case page of one ledger element (detail drawer). */
    @GetMapping("/api/v1/web-elements/{id}/referencing-cases")
    @RequirePermission("perm:project:project-view")
    public Response<ReferencingCasePage> referencingCases(
            @PathVariable long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        ReferencingCasePage result = client.referencingCases(id, page, pageSize);
        if (result.projectId() != null) {
            guard.assertCanView(UserContext.current(), result.projectId());
        }
        return Response.builderSuccess(result);
    }
}
