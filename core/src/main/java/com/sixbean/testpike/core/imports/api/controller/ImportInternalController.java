package com.sixbean.testpike.core.imports.api.controller;

import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.imports.api.dto.ImportResult;
import com.sixbean.testpike.core.imports.domain.service.ImportService;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;


@RestController
@RequestMapping("/internal/import")
public class ImportInternalController {

    private final ImportService importService;

    public ImportInternalController(ImportService importService) {
        this.importService = importService;
    }

    
    @PostMapping("/cases")
    public Response<ImportResult> importCases(@RequestParam long projectId,
                                              @RequestParam(required = false) Integer targetStatus,
                                              @RequestPart("file") MultipartFile file) throws IOException {
        return Response.builderSuccess(importService.importCases(OperatorContext.currentOperatorId(),
                projectId, file.getBytes(), file.getOriginalFilename(), targetStatus));
    }

    
    @PostMapping("/requirements")
    public Response<ImportResult> importRequirements(@RequestParam long projectId,
                                                     @RequestPart("file") MultipartFile file) throws IOException {
        return Response.builderSuccess(importService.importRequirements(OperatorContext.currentOperatorId(),
                projectId, file.getBytes(), file.getOriginalFilename()));
    }

    
    @PostMapping("/cases/retry")
    public Response<ImportResult> retryFailedCases(@RequestParam long projectId,
                                                   @RequestParam(required = false) Integer targetStatus,
                                                   @RequestParam List<Integer> retryRows,
                                                   @RequestPart("file") MultipartFile file) throws IOException {
        return Response.builderSuccess(importService.retryFailedCases(OperatorContext.currentOperatorId(),
                projectId, file.getBytes(), file.getOriginalFilename(), targetStatus, retryRows));
    }

    
    @GetMapping("/templates/cases")
    public Response<byte[]> caseTemplate() {
        return Response.builderSuccess(importService.caseTemplate());
    }

    
    @GetMapping("/templates/requirements")
    public Response<byte[]> requirementTemplate() {
        return Response.builderSuccess(importService.requirementTemplate());
    }
}
