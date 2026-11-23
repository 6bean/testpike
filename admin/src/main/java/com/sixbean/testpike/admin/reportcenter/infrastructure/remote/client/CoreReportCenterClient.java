package com.sixbean.testpike.admin.reportcenter.infrastructure.remote.client;

import com.sixbean.testpike.admin.reportcenter.api.dto.ReportCenterDetailView;
import com.sixbean.testpike.admin.reportcenter.api.dto.ReportCenterGenerateInput;
import com.sixbean.testpike.admin.reportcenter.api.dto.ReportCenterListItemView;
import com.sixbean.testpike.admin.reportcenter.api.dto.ReportCenterProgramOverviewView;
import com.sixbean.testpike.admin.reportcenter.api.dto.ReportCenterQueryInput;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import org.springframework.http.ResponseEntity;


public interface CoreReportCenterClient {

    
    PageView<ReportCenterListItemView> list(long operatorId, ReportCenterQueryInput query);

    /**
     * Program-lens dashboard feed passthrough. Core is the single decision point:
     * it narrows the feed to lens projects intersected with the caller's visibility
     * (operator rides X-Operator-Id), answers an empty feed to outsiders and raises
     * 40401 when the program does not exist — the bridge adds no second check.
     */
    ReportCenterProgramOverviewView programOverview(long operatorId, long programId,
            String from, String to);

    
    ReportCenterDetailView detail(long id);

 
    ReportCenterDetailView generate(long operatorId, ReportCenterGenerateInput input);

    
    ResponseEntity<byte[]> export(long operatorId, long id, String format, String theme);
}
