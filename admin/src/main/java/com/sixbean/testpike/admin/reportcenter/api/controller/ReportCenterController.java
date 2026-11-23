package com.sixbean.testpike.admin.reportcenter.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.reportcenter.api.dto.ReportCenterDetailView;
import com.sixbean.testpike.admin.reportcenter.api.dto.ReportCenterGenerateInput;
import com.sixbean.testpike.admin.reportcenter.api.dto.ReportCenterListItemView;
import com.sixbean.testpike.admin.reportcenter.api.dto.ReportCenterProgramOverviewView;
import com.sixbean.testpike.admin.reportcenter.api.dto.ReportCenterQueryInput;
import com.sixbean.testpike.admin.reportcenter.app.service.ReportCenterAdminService;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/report-center")
public class ReportCenterController {

    private final ReportCenterAdminService reportCenterAdminService;

    public ReportCenterController(ReportCenterAdminService reportCenterAdminService) {
        this.reportCenterAdminService = reportCenterAdminService;
    }

    
    @GetMapping
    public Response<PageView<ReportCenterListItemView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Integer reportType,
            @RequestParam(required = false) Integer scopeType,
            @RequestParam(required = false) Long scopeId,
            @RequestParam(required = false) String periodFrom,
            @RequestParam(required = false) String periodTo,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir,
            @RequestParam(defaultValue = "0") long programId) {
        CurrentUser user = UserContext.current();
        return Response.builderSuccess(reportCenterAdminService.list(user,
                new ReportCenterQueryInput(page, size, reportType, scopeType, scopeId,
                        periodFrom, periodTo, sortBy, sortDir, programId)));
    }

    /**
 * Program-lens dashboard feed (one row per visible project with a live pass-rate
 * reading + programmatic caliber footnotes). The window defaults to the last seven
 * days on core; the visibility segment is decided there, this bridge only forwards
 * the operator.
 */
    @GetMapping("/program-overview")
    public Response<ReportCenterProgramOverviewView> programOverview(
            @RequestParam long programId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        CurrentUser user = UserContext.current();
        return Response.builderSuccess(reportCenterAdminService.programOverview(user,
                programId, from, to));
    }

    
    @GetMapping("/{id}")
    public Response<ReportCenterDetailView> detail(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        return Response.builderSuccess(reportCenterAdminService.detail(user, id));
    }

    
    @RequirePermission("perm:ux:report-center")
    @PostMapping("/generate")
    public Response<ReportCenterDetailView> generate(@RequestBody ReportCenterGenerateInput input) {
        CurrentUser user = UserContext.current();
        return Response.builderSuccess(reportCenterAdminService.generate(user, input));
    }

    
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable long id,
                                         @RequestParam String format,
                                         @RequestParam(required = false) String theme) {
        CurrentUser user = UserContext.current();
        return reportCenterAdminService.export(user, id, format, theme);
    }
}
