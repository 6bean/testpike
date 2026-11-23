package com.sixbean.testpike.core.report.api.controller;

import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.report.api.dto.AiQualityView;
import com.sixbean.testpike.core.report.api.dto.CompareItemView;
import com.sixbean.testpike.core.report.api.dto.CoverageView;
import com.sixbean.testpike.core.report.api.dto.ExecutionStatView;
import com.sixbean.testpike.core.report.api.dto.ExportDownloadView;
import com.sixbean.testpike.core.report.api.dto.ExportResultView;
import com.sixbean.testpike.core.report.api.dto.ExportTaskView;
import com.sixbean.testpike.core.report.api.dto.PersonalReviewView;
import com.sixbean.testpike.core.report.api.dto.PhaseCount;
import com.sixbean.testpike.core.report.api.dto.TeamMemberStatView;
import com.sixbean.testpike.core.report.domain.service.ReportService;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/internal/reports")
public class ReportInternalController {

    private final ReportService reportService;

    public ReportInternalController(ReportService reportService) {
        this.reportService = reportService;
    }

    
    @GetMapping("/personal-review")
    public Response<PersonalReviewView> personalReview(@RequestParam long projectId,
                                                       @RequestParam(required = false) Long userId) {
        return Response.builderSuccess(reportService.personalReview(projectId, userId));
    }

    
    @GetMapping("/team-board")
    public Response<List<TeamMemberStatView>> teamBoard(@RequestParam long projectId) {
        return Response.builderSuccess(reportService.teamBoard(projectId));
    }

    
    @GetMapping("/ai-quality")
    public Response<AiQualityView> aiQuality(@RequestParam long projectId) {
        return Response.builderSuccess(reportService.aiQuality(projectId));
    }

    
    @GetMapping("/coverage")
    public Response<CoverageView> coverage(@RequestParam long projectId) {
        return Response.builderSuccess(reportService.coverage(projectId));
    }

 
    @GetMapping("/execution-stat")
    public Response<ExecutionStatView> executionStat(@RequestParam long projectId,
                                                     @RequestParam(required = false) Long planId) {
        return Response.builderSuccess(reportService.executionStat(projectId, planId));
    }

    
    @GetMapping("/compare")
    public Response<List<CompareItemView>> compare(@RequestParam String projectIds) {
        return Response.builderSuccess(reportService.compare(
                com.sixbean.testpike.common.util.IdListUtils.parseLongs(projectIds)));
    }

    
    @GetMapping("/phase-dist")
    public Response<List<PhaseCount>> phaseDist(@RequestParam long projectId) {
        return Response.builderSuccess(reportService.phaseDist(projectId));
    }

    
    @PostMapping("/export")
    public Response<ExportResultView> export(@RequestBody Map<String, Object> body) {
        long projectId = body.get("projectId") instanceof Number n ? n.longValue() : 0L;
        String reportType = body.get("reportType") == null ? null : String.valueOf(body.get("reportType"));
        boolean masked = Boolean.TRUE.equals(body.get("masked"));
        return Response.builderSuccess(reportService.export(
                OperatorContext.currentOperatorId(), projectId, reportType, masked));
    }

    
    @GetMapping("/export-tasks")
    public Response<List<ExportTaskView>> listExportTasks(@RequestParam long projectId) {
        return Response.builderSuccess(reportService.listExportTasks(projectId));
    }

    
    @GetMapping("/export-tasks/{id}/download")
    public Response<ExportDownloadView> download(@PathVariable long id, @RequestParam long projectId) {
        return Response.builderSuccess(reportService.exportTaskDownload(projectId, id));
    }
}
