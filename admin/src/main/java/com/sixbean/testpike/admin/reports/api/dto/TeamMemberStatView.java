package com.sixbean.testpike.admin.reports.api.dto;


public record TeamMemberStatView(long userId, String username, String nickName,
                                 long reviewedCount, long passCount, long needChangeCount,
                                 long rejectCount, long backlogCount) {
}
