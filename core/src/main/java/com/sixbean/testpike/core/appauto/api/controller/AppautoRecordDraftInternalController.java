package com.sixbean.testpike.core.appauto.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.appauto.api.dto.AppautoRecordConfirmRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoRecordDraftView;
import com.sixbean.testpike.core.appauto.domain.service.AppautoRecordDraftService;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/internal/appauto")
public class AppautoRecordDraftInternalController {

    private final AppautoRecordDraftService draftService;

    public AppautoRecordDraftInternalController(AppautoRecordDraftService draftService) {
        this.draftService = draftService;
    }

    
    @GetMapping("/record-drafts")
    public Response<List<AppautoRecordDraftView>> list(@RequestParam long caseId,
                                                       @RequestParam(required = false)
                                                       Integer status,
                                                       @RequestParam(defaultValue = "100")
                                                       int limit) {
        return Response.builderSuccess(draftService.list(caseId, status, limit));
    }

    
    @GetMapping("/record-drafts/{id}")
    public Response<AppautoRecordDraftView> find(@PathVariable long id) {
        return Response.builderSuccess(draftService.find(id));
    }

    
    @PostMapping("/record-drafts/{id}/confirm")
    public Response<AppautoRecordDraftView> confirm(@PathVariable long id,
                                                    @RequestBody AppautoRecordConfirmRequest
                                                            request) {
        return Response.builderSuccess(draftService.confirm(OperatorContext.currentOperatorId(),
                id, request.checkedStepNos(), request.expectedScriptVersion()));
    }

    
    @PostMapping("/record-drafts/{id}/reject")
    public Response<Void> reject(@PathVariable long id) {
        draftService.reject(OperatorContext.currentOperatorId(), id);
        return Response.builderSuccess(null);
    }
}
