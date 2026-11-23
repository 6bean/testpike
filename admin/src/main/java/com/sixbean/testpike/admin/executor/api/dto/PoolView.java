package com.sixbean.testpike.admin.executor.api.dto;

import java.util.List;


public record PoolView(long poolId, String poolName, long projectId, int memberCount,
                       int onlineCount, int capacitySum, int runningTaskCount,
                       int pendingTaskCount, List<PoolProjectView> projects) {
}
