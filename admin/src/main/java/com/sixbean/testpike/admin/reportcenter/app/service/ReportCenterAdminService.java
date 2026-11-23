package com.sixbean.testpike.admin.reportcenter.app.service;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.reportcenter.api.dto.ReportCenterDetailView;
import com.sixbean.testpike.admin.reportcenter.api.dto.ReportCenterGenerateInput;
import com.sixbean.testpike.admin.reportcenter.api.dto.ReportCenterListItemView;
import com.sixbean.testpike.admin.reportcenter.api.dto.ReportCenterProgramOverviewView;
import com.sixbean.testpike.admin.reportcenter.api.dto.ReportCenterQueryInput;
import org.springframework.http.ResponseEntity;


public interface ReportCenterAdminService {

    
    PageView<ReportCenterListItemView> list(CurrentUser user, ReportCenterQueryInput query);

    /** Program-lens dashboard feed (visibility segment is decided on core, no second guard here). */
    ReportCenterProgramOverviewView programOverview(CurrentUser user, long programId,
            String from, String to);

    
    ReportCenterDetailView detail(CurrentUser user, long id);

    
    ReportCenterDetailView generate(CurrentUser user, ReportCenterGenerateInput input);

    
    ResponseEntity<byte[]> export(CurrentUser user, long id, String format, String theme);
}
