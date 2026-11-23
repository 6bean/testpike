package com.sixbean.testpike.core.defects.api.controller;

import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.defects.api.dto.DefectInfo;
import com.sixbean.testpike.core.defects.api.dto.DefectLinkInput;
import com.sixbean.testpike.core.defects.api.dto.DefectStatusInput;
import com.sixbean.testpike.core.defects.domain.service.DefectService;
import com.sixbean.testpike.common.clientobject.Response;
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
@RequestMapping("/internal/defects")
public class DefectInternalController {

    private final DefectService defectService;

    public DefectInternalController(DefectService defectService) {
        this.defectService = defectService;
    }

    
    @PostMapping
    public Response<DefectInfo> link(@RequestBody DefectLinkInput input) {
        return Response.builderSuccess(defectService.link(OperatorContext.currentOperatorId(), input));
    }

    
    @PutMapping("/{id}/status")
    public Response<DefectInfo> updateStatus(@PathVariable long id, @RequestBody DefectStatusInput input) {
        return Response.builderSuccess(defectService.updateStatus(OperatorContext.currentOperatorId(), id, input));
    }

    
    @GetMapping
    public Response<List<DefectInfo>> listByCase(@RequestParam long caseId) {
        return Response.builderSuccess(defectService.listByCase(OperatorContext.currentOperatorId(), caseId));
    }

 
    @GetMapping("/{id}")
    public Response<DefectInfo> get(@PathVariable long id) {
        return Response.builderSuccess(defectService.get(id));
    }

    
    @PostMapping("/cases/{caseId}/rerun-hint")
    public Response<Long> rerunHint(@PathVariable long caseId) {
        return Response.builderSuccess(defectService.rerunHint(OperatorContext.currentOperatorId(), caseId));
    }
}
