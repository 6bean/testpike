package com.sixbean.testpike.admin.reports.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.reports.api.dto.AiQualityView;
import com.sixbean.testpike.admin.reports.api.dto.CompareItemView;
import com.sixbean.testpike.admin.reports.api.dto.CoverageView;
import com.sixbean.testpike.admin.reports.api.dto.ExecutionStatView;
import com.sixbean.testpike.admin.reports.api.dto.ExportDownloadView;
import com.sixbean.testpike.admin.reports.api.dto.ExportResultView;
import com.sixbean.testpike.admin.reports.api.dto.ExportTaskView;
import com.sixbean.testpike.admin.reports.api.dto.PersonalReviewView;
import com.sixbean.testpike.admin.reports.api.dto.PhaseCount;
import com.sixbean.testpike.admin.reports.api.dto.ReportDetailView;
import com.sixbean.testpike.admin.reports.api.dto.ReportExportRequest;
import com.sixbean.testpike.admin.reports.api.dto.ReportGenerateRequest;
import com.sixbean.testpike.admin.reports.api.dto.ReportRecordView;
import com.sixbean.testpike.admin.reports.api.dto.TeamMemberStatView;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.reports.app.service.ReportCompareService;
import com.sixbean.testpike.admin.reports.infrastructure.remote.client.CoreReportClient;
import com.sixbean.testpike.admin.reports.infrastructure.remote.client.CoreTestReportClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final CoreReportClient coreReportClient;
    private final CoreTestReportClient coreTestReportClient;
    private final ProjectAccessGuard guard;
    private final ReportCompareService compareService;

    public ReportController(CoreReportClient coreReportClient, CoreTestReportClient coreTestReportClient,
                            ProjectAccessGuard guard, ReportCompareService compareService) {
        this.coreReportClient = coreReportClient;
        this.coreTestReportClient = coreTestReportClient;
        this.guard = guard;
        this.compareService = compareService;
    }

    
    @RequirePermission("perm:batch:report-export")
    @GetMapping("/personal-review")
    public Response<PersonalReviewView> personalReview(@RequestParam long projectId,
                                                       @RequestParam(required = false) Long userId) {
        CurrentUser user = UserContext.current();
        guard.assertCanViewReport(user, projectId, "personal_review");
        Long target = userId == null ? user.userId() : userId;
        return Response.builderSuccess(coreReportClient.personalReview(projectId, target));
    }

    
    @RequirePermission("perm:project:project-report")
    @GetMapping("/team-board")
    public Response<List<TeamMemberStatView>> teamBoard(@RequestParam long projectId) {
        CurrentUser user = UserContext.current();
        guard.assertCanViewReport(user, projectId, "team_board");
        return Response.builderSuccess(coreReportClient.teamBoard(projectId));
    }

    
    @RequirePermission("perm:project:project-report")
    @GetMapping("/ai-quality")
    public Response<AiQualityView> aiQuality(@RequestParam long projectId) {
        CurrentUser user = UserContext.current();
        guard.assertCanViewReport(user, projectId, "ai_quality");
        return Response.builderSuccess(coreReportClient.aiQuality(projectId));
    }

    
    @RequirePermission("perm:project:project-report")
    @GetMapping("/coverage")
    public Response<CoverageView> coverage(@RequestParam long projectId) {
        CurrentUser user = UserContext.current();
        guard.assertCanViewReport(user, projectId, "coverage");
        return Response.builderSuccess(coreReportClient.coverage(projectId));
    }

    
    @RequirePermission("perm:project:project-report")
    @GetMapping("/execution-stat")
    public Response<ExecutionStatView> executionStat(@RequestParam long projectId,
                                                     @RequestParam(required = false) Long planId) {
        CurrentUser user = UserContext.current();
        guard.assertCanViewReport(user, projectId, "execution_stat");
        return Response.builderSuccess(coreReportClient.executionStat(projectId, planId));
    }

    
    @RequirePermission("perm:project:project-report")
    @GetMapping("/phase-dist")
    public Response<List<PhaseCount>> phaseDist(@RequestParam long projectId) {
        CurrentUser user = UserContext.current();
        guard.assertCanViewReport(user, projectId, "ai_quality");
        return Response.builderSuccess(coreReportClient.phaseDist(projectId));
    }

    
    @RequirePermission("perm:project:project-report")
    @GetMapping("/compare")
    public Response<List<CompareItemView>> compare(@RequestParam String projectIds) {
        CurrentUser user = UserContext.current();
        return Response.builderSuccess(compareService.compare(user,
                com.sixbean.testpike.common.util.IdListUtils.parseLongs(projectIds)));
    }

    
    @RequirePermission("perm:batch:report-export")
    @PostMapping("/export")
    public Response<ExportResultView> export(@RequestBody ReportExportRequest request) {
        CurrentUser user = UserContext.current();
        guard.assertCanExportReport(user, request.projectId());
        return Response.builderSuccess(coreReportClient.export(request));
    }

    
    @GetMapping("/export-tasks")
    public Response<List<ExportTaskView>> listExportTasks(@RequestParam long projectId) {
        CurrentUser user = UserContext.current();
        guard.assertCanView(user, projectId);
        return Response.builderSuccess(coreReportClient.listExportTasks(projectId));
    }

    
    @GetMapping("/export-tasks/{id}/download")
    public Response<ExportDownloadView> download(@PathVariable long id, @RequestParam long projectId) {
        CurrentUser user = UserContext.current();
        guard.assertCanView(user, projectId);
        return Response.builderSuccess(coreReportClient.exportTaskDownload(projectId, id));
    }


 
    @RequirePermission("perm:testops:summary-report")
    @PostMapping("/generate")
    public Response<ReportRecordView> generate(@RequestBody ReportGenerateRequest request) {
        CurrentUser user = UserContext.current();
        guard.assertCanManage(user, request.projectId());
        return Response.builderSuccess(coreTestReportClient.generate(request));
    }

    
    @GetMapping
    public Response<PageView<ReportRecordView>> listReports(@RequestParam long projectId,
                                                            @RequestParam(required = false) String keyword,
                                                            @RequestParam(defaultValue = "1") int page,
                                                            @RequestParam(defaultValue = "20") int size,
                                                            @RequestParam(required = false) String sortBy,
                                                            @RequestParam(required = false) String sortDir) {
        CurrentUser user = UserContext.current();
        guard.assertCanView(user, projectId);
        return Response.builderSuccess(
                coreTestReportClient.list(projectId, keyword, page, size, sortBy, sortDir));
    }

    
    @GetMapping("/{id}")
    public Response<ReportDetailView> getReport(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        ReportDetailView view = coreTestReportClient.get(id);
        guard.assertCanView(user, view.projectId());
        return Response.builderSuccess(view);
    }

    
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> exportReport(@PathVariable long id, @RequestParam String format) {
        CurrentUser user = UserContext.current();
        ReportDetailView view = coreTestReportClient.get(id);
        guard.assertCanView(user, view.projectId());
        byte[] data = coreTestReportClient.export(id, format);
        boolean pdf = "pdf".equalsIgnoreCase(format);
        String contentType = pdf ? "application/pdf"
                : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        String fileName = java.net.URLEncoder.encode(view.title() + (pdf ? ".pdf" : ".xlsx"),
                java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, contentType)
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + fileName)
                .body(data);
    }
}
