package com.sixbean.testpike.admin.requirement.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.cases.api.dto.CaseView;
import com.sixbean.testpike.admin.cases.infrastructure.remote.client.CoreCaseClient;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.requirement.api.dto.CaseBriefView;
import com.sixbean.testpike.admin.requirement.api.dto.RequirementVersionView;
import com.sixbean.testpike.admin.requirement.api.dto.CreateRequirementRequest;
import com.sixbean.testpike.admin.requirement.api.dto.LinkCasesRequest;
import com.sixbean.testpike.admin.requirement.api.dto.LinkResultView;
import com.sixbean.testpike.admin.requirement.api.dto.RequirementView;
import com.sixbean.testpike.admin.requirement.api.dto.TraceChainView;
import com.sixbean.testpike.admin.requirement.api.dto.UpdateRequirementRequest;
import com.sixbean.testpike.admin.requirement.infrastructure.remote.client.CoreRequirementClient;


import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
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


@RestController
@RequestMapping("/api/v1")
public class RequirementController {

    private final CoreRequirementClient coreRequirementClient;
    private final CoreCaseClient coreCaseClient;
    private final ProjectAccessGuard guard;

    public RequirementController(CoreRequirementClient coreRequirementClient,
                                 CoreCaseClient coreCaseClient, ProjectAccessGuard guard) {
        this.coreRequirementClient = coreRequirementClient;
        this.coreCaseClient = coreCaseClient;
        this.guard = guard;
    }

    /** Requirement list (status/keyword/paging/whitelisted sort; project members).
 * ids = optional comma-separated allow-list . */
    @GetMapping("/projects/{projectId}/requirements")
    public Response<PageView<RequirementView>> list(@PathVariable long projectId,
                                                    @RequestParam(required = false) Integer status,
                                                    @RequestParam(required = false) String keyword,
                                                    @RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "10") int size,
                                                    @RequestParam(required = false) String sortBy,
                                                    @RequestParam(required = false) String sortDir,
                                                    @RequestParam(required = false) String ids,
                                                    @RequestParam(required = false) String fingerprint) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(coreRequirementClient.listRequirements(
                projectId, status, keyword, page, size, sortBy, sortDir, ids, fingerprint));
    }

    /** Case-gap page for the generation-entry chips (project members, read-only;
     *  switch-off refusal from core passes through with its settings pointer). */
    @GetMapping("/projects/{projectId}/requirements/uncovered")
    public Response<PageView<RequirementView>> listUncovered(@PathVariable long projectId,
                                                             @RequestParam(defaultValue = "1") int page,
                                                             @RequestParam(defaultValue = "10") int size) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(coreRequirementClient.listUncoveredRequirements(projectId, page, size));
    }

    
    @PostMapping("/projects/{projectId}/requirements")
    public Response<RequirementView> create(@PathVariable long projectId,
                                            @RequestBody CreateRequirementRequest request) {
        guard.assertCanWriteRequirement(UserContext.current(), projectId);
        return Response.builderSuccess(coreRequirementClient.createRequirement(projectId, request));
    }

    
    @PutMapping("/projects/{projectId}/requirements/{reqId}")
    public Response<RequirementView> update(@PathVariable long projectId, @PathVariable long reqId,
                                            @RequestBody UpdateRequirementRequest request) {
        guard.assertCanWriteRequirement(UserContext.current(), projectId);
        return Response.builderSuccess(coreRequirementClient.updateRequirement(reqId, request));
    }

    
    @DeleteMapping("/projects/{projectId}/requirements/{reqId}")
    public Response<Long> delete(@PathVariable long projectId, @PathVariable long reqId) {
        guard.assertCanWriteRequirement(UserContext.current(), projectId);
        return Response.builderSuccess(coreRequirementClient.deleteRequirement(reqId));
    }

    
    @PostMapping("/requirements/{id}/cases")
    public Response<LinkResultView> linkCases(@PathVariable long id, @RequestBody LinkCasesRequest request) {
        RequirementView requirement = coreRequirementClient.getRequirement(id);
        guard.assertCanWriteCase(UserContext.current(), requirement.projectId());
        return Response.builderSuccess(coreRequirementClient.linkCases(id, request.caseIds()));
    }

    
    @DeleteMapping("/requirements/{id}/cases/{caseId}")
    public Response<Long> unlinkCase(@PathVariable long id, @PathVariable long caseId) {
        RequirementView requirement = coreRequirementClient.getRequirement(id);
        guard.assertCanWriteCase(UserContext.current(), requirement.projectId());
        return Response.builderSuccess(coreRequirementClient.unlinkCase(id, caseId));
    }

    
    @DeleteMapping("/cases/{caseId}/requirements/{reqId}")
    public Response<Long> unlinkCaseFromCase(@PathVariable long caseId, @PathVariable long reqId) {
        CaseView caseView = coreCaseClient.getCase(caseId);
        guard.assertCanWriteCase(UserContext.current(), caseView.projectId());
        boolean linked = coreRequirementClient.listRequirementsByCaseId(caseId).stream()
                .anyMatch(r -> r.id() != null && r.id() == reqId);
        if (!linked) {
            throw new ServiceException("该用例与需求点 %s 不存在关联（可能已被取消）——"
                    + "请在「测试用例」打开该用例详情的需求点列表，刷新确认仍在列表中的需求点再操作取消关联",
                    BizErrorCode.NOT_FOUND, reqId);
        }
        return Response.builderSuccess(coreRequirementClient.unlinkCase(reqId, caseId));
    }

    
    @GetMapping("/cases/{caseId}/requirements")
    public Response<List<RequirementView>> listByCaseId(@PathVariable long caseId) {
        CaseView caseView = coreCaseClient.getCase(caseId);
        guard.assertCanView(UserContext.current(), caseView.projectId());
        return Response.builderSuccess(coreRequirementClient.listRequirementsByCaseId(caseId));
    }

    
    @GetMapping("/requirements/{id}/cases")
    public Response<List<CaseBriefView>> listLinkedCases(@PathVariable long id) {
        RequirementView requirement = coreRequirementClient.getRequirement(id);
        guard.assertCanView(UserContext.current(), requirement.projectId());
        return Response.builderSuccess(coreRequirementClient.listCasesByRequirementId(id));
    }

    /** Requirement version chain for the ledger page version dialog (project member, cross-project 403). */
    @GetMapping("/requirements/{id}/versions")
    public Response<List<RequirementVersionView>> listRequirementVersions(@PathVariable long id) {
        RequirementView requirement = coreRequirementClient.getRequirement(id);
        guard.assertCanView(UserContext.current(), requirement.projectId());
        return Response.builderSuccess(coreRequirementClient.listRequirementVersions(id));
    }

 
    @RequirePermission("perm:ux:trace-view")
    @GetMapping("/requirements/{id}/trace-chain")
    public Response<TraceChainView> requirementTraceChain(@PathVariable long id) {
        RequirementView requirement = coreRequirementClient.getRequirement(id);
        guard.assertCanView(UserContext.current(), requirement.projectId());
        return Response.builderSuccess(coreRequirementClient.traceChainOfRequirement(id));
    }

 
    @RequirePermission("perm:ux:trace-view")
    @GetMapping("/cases/{caseId}/trace-chain")
    public Response<TraceChainView> caseTraceChain(@PathVariable long caseId) {
        CaseView caseView = coreCaseClient.getCase(caseId);
        guard.assertCanView(UserContext.current(), caseView.projectId());
        return Response.builderSuccess(coreRequirementClient.traceChainOfCase(caseId));
    }
}
