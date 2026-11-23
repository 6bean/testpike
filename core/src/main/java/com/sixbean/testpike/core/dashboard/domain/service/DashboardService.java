package com.sixbean.testpike.core.dashboard.domain.service;

import com.sixbean.testpike.core.dashboard.api.dto.DashboardOverview;


public interface DashboardService {

    
    DashboardOverview overview(long userId, boolean allVisible);
}
