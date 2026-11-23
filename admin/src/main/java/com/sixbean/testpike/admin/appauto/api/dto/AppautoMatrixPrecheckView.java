package com.sixbean.testpike.admin.appauto.api.dto;


public record AppautoMatrixPrecheckView(
        int fanoutTasks,
        int minutesPerTask,
        long aheadPendingTasks,
        long idleCapacity,
        long estQueueMinutes,
        String estimatedStartAt,
        int fanoutMaxAllowed,
        boolean budgetHighWater,
        String message) {
}
