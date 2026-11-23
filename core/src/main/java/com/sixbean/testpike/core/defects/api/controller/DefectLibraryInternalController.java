package com.sixbean.testpike.core.defects.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.defects.api.dto.DefectCreateInput;
import com.sixbean.testpike.core.defects.api.dto.DefectExportRequest;
import com.sixbean.testpike.core.defects.api.dto.DefectExportResult;
import com.sixbean.testpike.core.defects.api.dto.DefectLibraryInfo;
import com.sixbean.testpike.core.defects.api.dto.DefectQuery;
import com.sixbean.testpike.core.defects.api.dto.DefectSummary;
import com.sixbean.testpike.core.defects.api.dto.DefectTransitionInput;
import com.sixbean.testpike.core.defects.api.dto.DefectUpdateInput;
import com.sixbean.testpike.core.defects.domain.service.DefectLibraryService;
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
@RequestMapping("/internal/defect-library")
public class DefectLibraryInternalController {

    private final DefectLibraryService defectLibraryService;

    public DefectLibraryInternalController(DefectLibraryService defectLibraryService) {
        this.defectLibraryService = defectLibraryService;
    }

    
    @PostMapping
    public Response<DefectLibraryInfo> create(@RequestBody DefectCreateInput input) {
        return Response.builderSuccess(
                defectLibraryService.create(OperatorContext.currentOperatorId(), input));
    }

    
    @GetMapping
    public Response<PageResult<DefectLibraryInfo>> list(@RequestParam long projectId,
                                                 @RequestParam(required = false) Integer status,
                                                 @RequestParam(required = false) Integer severity,
                                                 @RequestParam(required = false) Long assigneeId,
                                                 @RequestParam(required = false) Long caseId,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "10") int size,
                                                 @RequestParam(required = false) String sortBy,
                                                 @RequestParam(required = false) String sortDir,
                                                 @RequestParam(defaultValue = "0") long programId) {
        return Response.builderSuccess(defectLibraryService.list(OperatorContext.currentOperatorId(),
                new DefectQuery(projectId, status, severity, assigneeId, caseId, keyword, page, size,
                        sortBy, sortDir, programId)));
    }

 
    @PostMapping("/export")
    public Response<DefectExportResult> export(@RequestBody DefectExportRequest request) {
        return Response.builderSuccess(defectLibraryService.export(
                OperatorContext.currentOperatorId(), request));
    }

    
    @GetMapping("/{id}")
    public Response<DefectLibraryInfo> get(@PathVariable long id) {
        return Response.builderSuccess(defectLibraryService.get(id));
    }

    
    @PutMapping("/{id}")
    public Response<DefectLibraryInfo> update(@PathVariable long id, @RequestBody DefectUpdateInput input) {
        return Response.builderSuccess(
                defectLibraryService.update(OperatorContext.currentOperatorId(), id, input));
    }

    
    @PostMapping("/{id}/transition")
    public Response<DefectLibraryInfo> transition(@PathVariable long id, @RequestBody DefectTransitionInput input) {
        return Response.builderSuccess(
                defectLibraryService.transition(OperatorContext.currentOperatorId(), id, input));
    }

 
    @GetMapping("/summary")
    public Response<DefectSummary> summaryByCase(@RequestParam long caseId) {
        return Response.builderSuccess(defectLibraryService.summaryByCase(caseId));
    }
}
