package com.sixbean.testpike.core.testreport.domain.service;

import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.testreport.api.dto.ReportDetailView;
import com.sixbean.testpike.core.testreport.api.dto.ReportGenerateInput;
import com.sixbean.testpike.core.testreport.api.dto.ReportQuery;
import com.sixbean.testpike.core.testreport.api.dto.ReportRecordInfo;


public interface TestReportService {

 
    ReportRecordInfo generate(long operatorId, ReportGenerateInput input);

    
    PageResult<ReportRecordInfo> list(ReportQuery query);

    
    ReportDetailView get(long id);

    
    byte[] export(long id, String format);
}
