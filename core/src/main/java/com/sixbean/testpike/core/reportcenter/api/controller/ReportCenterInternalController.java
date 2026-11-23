package com.sixbean.testpike.core.reportcenter.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.reportcenter.api.dto.ReportExportFile;
import com.sixbean.testpike.core.reportcenter.api.dto.ReportGenerateInput;
import com.sixbean.testpike.core.reportcenter.api.dto.ReportListItem;
import com.sixbean.testpike.core.reportcenter.api.dto.ReportQuery;
import com.sixbean.testpike.core.reportcenter.api.dto.ProgramOverview;
import com.sixbean.testpike.core.reportcenter.api.dto.ReportView;
import com.sixbean.testpike.core.reportcenter.domain.service.ProgramOverviewService;
import com.sixbean.testpike.core.reportcenter.domain.service.ReportCenterService;
import com.sixbean.testpike.core.reportcenter.domain.service.ReportGenerateService;
import com.sixbean.testpike.core.reportcenter.domain.service.ReportRetentionService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;


@RestController
@RequestMapping("/internal/report-center")
public class ReportCenterInternalController {

    private final ReportCenterService reportCenterService;
    private final ReportGenerateService reportGenerateService;
    private final ReportRetentionService reportRetentionService;
    private final ProgramOverviewService programOverviewService;

    public ReportCenterInternalController(ReportCenterService reportCenterService,
                                          ReportGenerateService reportGenerateService,
                                          ReportRetentionService reportRetentionService,
                                          ProgramOverviewService programOverviewService) {
        this.reportCenterService = reportCenterService;
        this.reportGenerateService = reportGenerateService;
        this.reportRetentionService = reportRetentionService;
        this.programOverviewService = programOverviewService;
    }

    
    @PostMapping("/generate")
    public Response<ReportView> generate(@RequestBody ReportGenerateInput input) {
        return Response.builderSuccess(
                reportCenterService.generate(OperatorContext.currentOperatorId(), input));
    }

    /**
     * Program-lens dashboard feed (per-project live pass-rate + registry footnotes).
     * Period parameters are optional ISO date-times; missing = last 7 days window.
     */
    @GetMapping("/program-overview")
    public Response<ProgramOverview> programOverview(
            @RequestParam long programId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return Response.builderSuccess(programOverviewService.overview(
                programId, OperatorContext.currentOperatorId(), from, to));
    }

    
    @GetMapping
    public Response<PageResult<ReportListItem>> list(
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
        return Response.builderSuccess(reportCenterService.list(
                new ReportQuery(page, size, reportType, scopeType, scopeId, periodFrom, periodTo,
                        sortBy, sortDir, programId), OperatorContext.currentOperatorId()));
    }

    
    @GetMapping("/{id}")
    public Response<ReportView> detail(@PathVariable long id) {
        return Response.builderSuccess(reportCenterService.detail(id));
    }

    
    @GetMapping(value = "/{id}/export", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> export(@PathVariable long id,
                                         @RequestParam String format,
                                         @RequestParam(required = false) String theme) {
        ReportExportFile file = reportCenterService.export(
                OperatorContext.currentOperatorId(), id, format, theme);
        String encoded = URLEncoder.encode(file.fileName(), StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, file.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .body(file.content());
    }

    
    @PostMapping("/weekly/run")
    public Response<Integer> runWeekly() {
        return Response.builderSuccess(reportGenerateService.runWeekly());
    }

    
    @PostMapping("/monthly/run")
    public Response<Integer> runMonthly() {
        return Response.builderSuccess(reportGenerateService.runMonthly());
    }

    
    @PostMapping("/retention/run")
    public Response<Integer> runRetention() {
        return Response.builderSuccess(reportRetentionService.deleteExpired());
    }
}
