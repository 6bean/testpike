package com.sixbean.testpike.admin.apitest.api.controller;

import com.sixbean.testpike.admin.apitest.api.dto.RunSetCreateRequest;
import com.sixbean.testpike.admin.apitest.api.dto.RunSetDetailView;
import com.sixbean.testpike.admin.apitest.api.dto.RunSetPageView;
import com.sixbean.testpike.admin.apitest.api.dto.RunSetPreviewStepView;
import com.sixbean.testpike.admin.apitest.api.dto.RunSetView;
import com.sixbean.testpike.admin.apitest.app.component.ApiTestAccessResolver;
import com.sixbean.testpike.admin.apitest.infrastructure.remote.client.CoreApiRunSetClient;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Whole-collection run endpoints .
 * <p>Guards: launching = write permission resolved from the collection's owning
 * project (never trusts a client-supplied project); detail/history = project
 * membership via the projectId the core ledger already carries (one hop saved —
 * the ledger row is the authority, the bridge never re-derives it from the client).
 * Env cross-project 400 and mode validation stay in core (single source).
 */
@RestController
@RequestMapping("/api/v1/apitest/run-sets")
public class ApiRunSetController {

    private final CoreApiRunSetClient coreApiRunSetClient;
    private final ApiTestAccessResolver accessResolver;
    private final ProjectAccessGuard guard;

    public ApiRunSetController(CoreApiRunSetClient coreApiRunSetClient,
                               ApiTestAccessResolver accessResolver,
                               ProjectAccessGuard guard) {
        this.coreApiRunSetClient = coreApiRunSetClient;
        this.accessResolver = accessResolver;
        this.guard = guard;
    }

    /** Launch a collection run (missing collection 404; foreign env 400 passed through). */
    @PostMapping
    @RequirePermission("perm:detail:case-tag")
    public Response<RunSetView> create(@RequestBody RunSetCreateRequest request) {
        guard.assertCanWriteCase(UserContext.current(),
                accessResolver.projectIdOfCollection(request.collectionId()));
        return Response.builderSuccess(coreApiRunSetClient.createRunSet(request));
    }

    /** Run detail: ledger plus child steps in seq_no order (H1 isolation via ledger projectId). */
    @GetMapping("/{id}")
    @RequirePermission("perm:detail:case-tag")
    public Response<RunSetDetailView> detail(@PathVariable long id) {
        RunSetDetailView detail = coreApiRunSetClient.getRunSetDetail(id);
        guard.assertCanView(UserContext.current(), detail.ledger().projectId());
        return Response.builderSuccess(detail);
    }

    /**
 * Launch-preview card: tree order + dependency arrows for a collection
 * . Read guard = view permission on the collection's owning
 * project; the graph itself is core's single-source computation.
 * Note: registered as a literal path so it never collides with /{id}.
 */
    @GetMapping("/previews")
    @RequirePermission("perm:detail:case-tag")
    public Response<List<RunSetPreviewStepView>> preview(@RequestParam long collectionId) {
        guard.assertCanView(UserContext.current(),
                accessResolver.projectIdOfCollection(collectionId));
        return Response.builderSuccess(coreApiRunSetClient.previewRunSet(collectionId));
    }

    /** One paged window of the collection's run-set history, newest first (H1 isolation). */
    @GetMapping
    @RequirePermission("perm:detail:case-tag")
    public Response<RunSetPageView> listByCollection(@RequestParam long collectionId,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "20") int pageSize) {
        guard.assertCanView(UserContext.current(),
                accessResolver.projectIdOfCollection(collectionId));
        return Response.builderSuccess(coreApiRunSetClient.listRunSetsByCollection(collectionId, page, pageSize));
    }
}
