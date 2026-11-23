package com.sixbean.testpike.admin.apitest.api.controller;

import com.sixbean.testpike.admin.apitest.api.dto.FlowTemplatePageDto;
import com.sixbean.testpike.admin.apitest.api.dto.FlowTemplateSaveRequest;
import com.sixbean.testpike.admin.apitest.api.dto.FlowTemplateView;
import com.sixbean.testpike.admin.apitest.api.dto.RequestView;
import com.sixbean.testpike.admin.apitest.app.component.ApiTestAccessResolver;
import com.sixbean.testpike.admin.apitest.infrastructure.remote.client.CoreFlowTemplateClient;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Flow-template user endpoints ; 02 contract
 * /api/v1/apitest/flow-templates).
 * <p>Guards follow the run-set posture: saving = write permission resolved from
 * the source collection's owning project (never trusts a client-supplied
 * project); listing = view permission on the requested project segment;
 * instantiate = write permission on the TARGET collection's project (a
 * cross-project template only lands where the user may write); delete = write
 * on the template's own project. Permission key reused from the apitest family
 * (catalog scan: domain carries no dedicated write key — same precedent as the
 * reorder door, zero new keys).
 */
@RestController
@RequestMapping("/api/v1/apitest/flow-templates")
public class FlowTemplateController {

    private final CoreFlowTemplateClient coreFlowTemplateClient;
    private final ApiTestAccessResolver accessResolver;
    private final ProjectAccessGuard guard;

    public FlowTemplateController(CoreFlowTemplateClient coreFlowTemplateClient,
                                  ApiTestAccessResolver accessResolver,
                                  ProjectAccessGuard guard) {
        this.coreFlowTemplateClient = coreFlowTemplateClient;
        this.accessResolver = accessResolver;
        this.guard = guard;
    }

    /** Freeze a collection into a template (empty collection 400 passed through). */
    @PostMapping
    @RequirePermission("perm:detail:case-tag")
    public Response<FlowTemplateView> save(@RequestBody FlowTemplateSaveRequest request) {
        guard.assertCanWriteCase(UserContext.current(),
                accessResolver.projectIdOfCollection(request.collectionId()));
        return Response.builderSuccess(coreFlowTemplateClient.saveTemplate(request));
    }

    /** One paged window of the project's templates, newest first (H1 isolation). */
    @GetMapping
    @RequirePermission("perm:detail:case-tag")
    public Response<FlowTemplatePageDto> list(@RequestParam long projectId,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "20") int pageSize) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(coreFlowTemplateClient.listTemplates(projectId, page, pageSize));
    }

    /** Template detail (404 when missing; H1 via the row's own projectId). */
    @GetMapping("/{id}")
    @RequirePermission("perm:detail:case-tag")
    public Response<FlowTemplateView> get(@PathVariable long id) {
        FlowTemplateView template = coreFlowTemplateClient.getTemplate(id);
        guard.assertCanView(UserContext.current(), template.projectId());
        return Response.builderSuccess(template);
    }

    /** Instantiate into a target collection — write guard on the TARGET project. */
    @PostMapping("/{id}/instantiate")
    @RequirePermission("perm:detail:case-tag")
    public Response<List<RequestView>> instantiate(@PathVariable long id,
                                                   @RequestBody Map<String, Long> body) {
        Long targetCollectionId = body == null ? null : body.get("targetCollectionId");
        if (targetCollectionId == null) {
            throw new ServiceException(
                    "实例化必须指定目标集合", BizErrorCode.VALIDATION_ERROR);
        }
        guard.assertCanWriteCase(UserContext.current(),
                accessResolver.projectIdOfCollection(targetCollectionId));
        return Response.builderSuccess(
                coreFlowTemplateClient.instantiateTemplate(id, targetCollectionId));
    }

    /** Delete a template (write guard on the template's owning project). */
    @DeleteMapping("/{id}")
    @RequirePermission("perm:detail:case-tag")
    public Response<Void> delete(@PathVariable long id) {
        FlowTemplateView template = coreFlowTemplateClient.getTemplate(id);
        guard.assertCanWriteCase(UserContext.current(), template.projectId());
        coreFlowTemplateClient.deleteTemplate(id);
        return Response.builderSuccess(null);
    }
}
