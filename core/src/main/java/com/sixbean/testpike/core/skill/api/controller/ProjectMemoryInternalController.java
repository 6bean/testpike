package com.sixbean.testpike.core.skill.api.controller;

import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.skill.api.dto.ProjectMemoryCreateInput;
import com.sixbean.testpike.core.skill.api.dto.ProjectMemoryInfo;
import com.sixbean.testpike.core.skill.domain.service.ProjectMemoryLedgerService;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Project-memory ledger internal endpoints .
 * <p>Thin controller: parameter pass-through only. Permission checks stay on the
 * admin side (H1 isolation baseline, same shape as SkillInternalController), the
 * operator travels through X-Operator-Id for audit attribution, and the whole
 * state machine lives in ProjectMemoryLedgerService.
 */
@RestController
@RequestMapping("/internal/project-memory")
public class ProjectMemoryInternalController {

    private final ProjectMemoryLedgerService ledgerService;

    public ProjectMemoryInternalController(ProjectMemoryLedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @GetMapping
    public Response<PageResult<ProjectMemoryInfo>> list(@RequestParam Long projectId,
                                                        @RequestParam(required = false) String keyword,
                                                        @RequestParam(required = false) Integer injectStatus,
                                                        @RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        return Response.builderSuccess(ledgerService.list(projectId, keyword, injectStatus, page, size));
    }

    @PostMapping
    public Response<ProjectMemoryInfo> create(
            @RequestHeader(name = OperatorContext.OPERATOR_ID_HEADER, required = false) Long operatorId,
            @RequestBody ProjectMemoryCreateInput input) {
        return Response.builderSuccess(ledgerService.create(operatorId, input));
    }

    @PutMapping("/{id}/status")
    public Response<ProjectMemoryInfo> setStatus(
            @RequestHeader(name = OperatorContext.OPERATOR_ID_HEADER, required = false) Long operatorId,
            @PathVariable long id,
            @RequestParam Long projectId,
            @RequestParam int status) {
        return Response.builderSuccess(ledgerService.setInjectStatus(operatorId, projectId, id, status));
    }

    @PostMapping("/{id}/reject")
    public Response<ProjectMemoryInfo> reject(
            @RequestHeader(name = OperatorContext.OPERATOR_ID_HEADER, required = false) Long operatorId,
            @PathVariable long id,
            @RequestParam Long projectId) {
        return Response.builderSuccess(ledgerService.reject(operatorId, projectId, id));
    }

    @DeleteMapping("/{id}")
    public Response<Void> delete(
            @RequestHeader(name = OperatorContext.OPERATOR_ID_HEADER, required = false) Long operatorId,
            @PathVariable long id,
            @RequestParam Long projectId) {
        ledgerService.delete(operatorId, projectId, id);
        return Response.builderSuccess();
    }

    @GetMapping("/recycle-bin")
    public Response<PageResult<ProjectMemoryInfo>> recycleBin(@RequestParam Long projectId,
                                                              @RequestParam(defaultValue = "1") int page,
                                                              @RequestParam(defaultValue = "20") int size) {
        return Response.builderSuccess(ledgerService.recycleBin(projectId, page, size));
    }

    @PostMapping("/{id}/restore")
    public Response<ProjectMemoryInfo> restore(
            @RequestHeader(name = OperatorContext.OPERATOR_ID_HEADER, required = false) Long operatorId,
            @PathVariable long id,
            @RequestParam Long projectId) {
        return Response.builderSuccess(ledgerService.restore(operatorId, projectId, id));
    }
}
