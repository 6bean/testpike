package com.sixbean.testpike.admin.reports.app.service;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.reports.api.dto.CompareItemView;

import java.util.List;


public interface ReportCompareService {

    
    List<CompareItemView> compare(CurrentUser user, List<Long> projectIds);
}
