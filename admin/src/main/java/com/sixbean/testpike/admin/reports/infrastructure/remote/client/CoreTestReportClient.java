package com.sixbean.testpike.admin.reports.infrastructure.remote.client;

import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.reports.api.dto.ReportDetailView;
import com.sixbean.testpike.admin.reports.api.dto.ReportGenerateRequest;
import com.sixbean.testpike.admin.reports.api.dto.ReportRecordView;


public interface CoreTestReportClient {

 
    ReportRecordView generate(ReportGenerateRequest request);

    
    PageView<ReportRecordView> list(long projectId, String keyword, int page, int size,
                                    String sortBy, String sortDir);

    
    ReportDetailView get(long id);

    
    byte[] export(long id, String format);
}
