package com.sixbean.testpike.admin.dashboard.infrastructure.remote.client;

import com.sixbean.testpike.admin.dashboard.api.dto.DashboardOverviewView;


public interface CoreDashboardClient {

    
    DashboardOverviewView overview(boolean allVisible);
}
