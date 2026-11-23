package com.sixbean.testpike.core.coverage.api.controller;

import com.sixbean.testpike.core.coverage.api.dto.CoverageMatrixView;
import com.sixbean.testpike.core.coverage.domain.service.CoverageMatrixService;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/internal/projects")
public class CoverageInternalController {

    private final CoverageMatrixService coverageMatrixService;

    public CoverageInternalController(CoverageMatrixService coverageMatrixService) {
        this.coverageMatrixService = coverageMatrixService;
    }

    
    @GetMapping("/{projectId}/coverage")
    public Response<CoverageMatrixView> matrix(@PathVariable long projectId,
                                               @RequestParam(required = false) String chapter,
                                               @RequestParam(required = false) Integer priority,
                                               @RequestParam(required = false) String endType) {
        return Response.builderSuccess(coverageMatrixService.matrix(projectId, chapter, priority, endType));
    }
}
