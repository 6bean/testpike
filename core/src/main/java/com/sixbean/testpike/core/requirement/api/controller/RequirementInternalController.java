package com.sixbean.testpike.core.requirement.api.controller;

import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.requirement.api.dto.CaseOfflineReqCheckInput;
import com.sixbean.testpike.core.requirement.api.dto.RequirementCreateInput;
import com.sixbean.testpike.core.requirement.api.dto.RequirementInfo;
import com.sixbean.testpike.core.requirement.api.dto.RequirementVersionInfo;
import com.sixbean.testpike.core.requirement.api.dto.RequirementLinkInput;
import com.sixbean.testpike.core.requirement.api.dto.RequirementLinkResult;
import com.sixbean.testpike.core.requirement.api.dto.RequirementQuery;
import com.sixbean.testpike.core.requirement.api.dto.RequirementUpdateInput;
import com.sixbean.testpike.core.requirement.domain.service.RequirementService;


import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
public class RequirementInternalController {

    private final RequirementService requirementService;

    public RequirementInternalController(RequirementService requirementService) {
        this.requirementService = requirementService;
    }

    /** Requirement list (status/keyword/paging/whitelisted sort; projectId required, H1 scope).
 * Optional ids = comma-separated allow-list for the structured deep-link handoff. */
    @GetMapping("/internal/requirements")
    public Response<PageResult<RequirementInfo>> list(@RequestParam long projectId,
                                                      @RequestParam(required = false) Integer status,
                                                      @RequestParam(required = false) String keyword,
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "10") int size,
                                                      @RequestParam(required = false) String sortBy,
                                                      @RequestParam(required = false) String sortDir,
                                                      @RequestParam(required = false) String ids,
                                                      @RequestParam(required = false) String fingerprint) {
        return Response.builderSuccess(requirementService.list(
                new RequirementQuery(projectId, status, keyword, page, size, sortBy, sortDir,
                        parseIdList(ids), fingerprint)));
    }

    /** Comma-separated id list → Long list; blank = no filter. A malformed token is a
     *  validation error (fail-closed), never a silently dropped id. */
    private static java.util.List<Long> parseIdList(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        java.util.List<Long> parsed = new java.util.ArrayList<>();
        for (String token : raw.split(",")) {
            if (token.isBlank()) {
                continue;
            }
            try {
                parsed.add(Long.parseLong(token.trim()));
            } catch (NumberFormatException e) {
                throw new com.sixbean.testpike.common.exception.ServiceException(
                        "需求点 ID 清单格式不合法（须为逗号分隔的数字）",
                        com.sixbean.testpike.common.exception.BizErrorCode.VALIDATION_ERROR);
            }
        }
        return parsed;
    }

    /** Case-gap page: this project's requirements with no covering live case
     *  (generation-entry chips; core refuses with a settings pointer while
     *  ai.doc.case-gap-suggest is off). */
    @GetMapping("/internal/requirements/uncovered")
    public Response<PageResult<RequirementInfo>> listUncovered(@RequestParam long projectId,
                                                               @RequestParam(defaultValue = "1") int page,
                                                               @RequestParam(defaultValue = "10") int size) {
        return Response.builderSuccess(requirementService.listUncovered(projectId, page, size));
    }

    
    @GetMapping("/internal/requirements/{id}")
    public Response<RequirementInfo> get(@PathVariable long id) {
        return Response.builderSuccess(requirementService.get(id));
    }

    /** Requirement version chain, newest first (ledger page version dialog read surface). */
    @GetMapping("/internal/requirements/{id}/versions")
    public Response<List<RequirementVersionInfo>> listVersions(@PathVariable long id) {
        return Response.builderSuccess(requirementService.listVersions(id));
    }

 
    @PostMapping("/internal/requirements")
    public Response<RequirementInfo> create(@RequestBody RequirementCreateInput input) {
        return Response.builderSuccess(requirementService.create(OperatorContext.currentOperatorId(), input));
    }

    
    @PutMapping("/internal/requirements/{id}")
    public Response<RequirementInfo> update(@PathVariable long id, @RequestBody RequirementUpdateInput input) {
        return Response.builderSuccess(requirementService.update(OperatorContext.currentOperatorId(), id, input));
    }

    
    @DeleteMapping("/internal/requirements/{id}")
    public Response<Long> delete(@PathVariable long id) {
        return Response.builderSuccess(requirementService.delete(OperatorContext.currentOperatorId(), id));
    }

    
    @PostMapping("/internal/requirements/{id}/cases")
    public Response<RequirementLinkResult> linkCases(@PathVariable long id, @RequestBody RequirementLinkInput input) {
        return Response.builderSuccess(requirementService.linkCases(
                OperatorContext.currentOperatorId(), id, input.caseIds()));
    }

    
    @DeleteMapping("/internal/requirements/{id}/cases/{caseId}")
    public Response<Long> unlinkCase(@PathVariable long id, @PathVariable long caseId) {
        return Response.builderSuccess(requirementService.unlinkCase(
                OperatorContext.currentOperatorId(), id, caseId));
    }

    
    @GetMapping("/internal/cases/{caseId}/requirements")
    public Response<List<RequirementInfo>> listByCaseId(@PathVariable long caseId) {
        return Response.builderSuccess(requirementService.listByCaseId(caseId));
    }

    
    @GetMapping("/internal/requirements/{id}/cases")
    public Response<List<CaseInfo>> listCases(@PathVariable long id) {
        return Response.builderSuccess(requirementService.listCasesByRequirementId(id));
    }

    
    @PostMapping("/internal/requirements/cases/offline-check")
    public Response<List<Long>> offlineCaseIds(@RequestBody CaseOfflineReqCheckInput input) {
        return Response.builderSuccess(
                requirementService.offlineCaseIds(input.projectId(), input.caseIds()));
    }
}
