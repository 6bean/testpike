package com.sixbean.testpike.admin.dashboard.api.dto;

import java.util.List;


public record DashboardOverviewView(long pendingReviewCount, long overdueReviewCount,
                                    long runningTaskCount,
                                    long unclosedDefectCount, List<ProjectStatView> projects,
                                    List<ActivityItemView> activities,
                                    long unassignedReviewCount) {
}
