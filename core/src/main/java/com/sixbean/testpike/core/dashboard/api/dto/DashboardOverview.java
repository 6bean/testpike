package com.sixbean.testpike.core.dashboard.api.dto;

import java.util.List;


public record DashboardOverview(long pendingReviewCount, long overdueReviewCount,
                                long runningTaskCount,
                                long unclosedDefectCount, List<ProjectStat> projects,
                                List<ActivityItem> activities,
                                long unassignedReviewCount) {
}
