package com.sixbean.testpike.admin.executor.api.dto;


public record PoolProjectView(long projectId, String projectName, Integer quotaPct,
                              int slotCap, int runningInPool, int pendingCount) {
}
