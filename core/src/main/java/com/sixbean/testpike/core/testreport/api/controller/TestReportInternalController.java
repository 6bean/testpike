package com.sixbean.testpike.core.testreport.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.testreport.api.dto.ReportDetailView;
import com.sixbean.testpike.core.testreport.api.dto.ReportGenerateInput;
import com.sixbean.testpike.core.testreport.api.dto.ReportQuery;
import com.sixbean.testpike.core.testreport.api.dto.ReportRecordInfo;
import com.sixbean.testpike.core.testreport.domain.service.TestReportService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/internal/test-reports")
public class TestReportInternalController {

    private final TestReportService reportService;

    public TestReportInternalController(TestReportService reportService) {
        this.reportService = reportService;
    }

 
    @PostMapping("/generate")
    public Response<ReportRecordInfo> generate(@RequestBody ReportGenerateInput input) {
        return Response.builderSuccess(
                reportService.generate(OperatorContext.currentOperatorId(), input));
    }

    
    @GetMapping
    public Response<PageResult<ReportRecordInfo>> list(@RequestParam long projectId,
                                                       @RequestParam(required = false) String keyword,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "20") int size,
                                                       @RequestParam(required = false) String sortBy,
                                                       @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(
                reportService.list(new ReportQuery(projectId, keyword, page, size, sortBy, sortDir)));
    }

    
    @GetMapping("/{id}")
    public Response<ReportDetailView> get(@PathVariable long id) {
        return Response.builderSuccess(reportService.get(id));
    }

    
    @GetMapping(value = "/{id}/export", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public byte[] export(@PathVariable long id, @RequestParam String format) {
        return reportService.export(id, format);
    }
}
