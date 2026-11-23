package com.sixbean.testpike.core.appauto.api.dto;


public record AppautoMatrixPrecheckView(int fanoutTasks, int minutesPerTask,
                                        long aheadPendingTasks, long idleCapacity,
                                        long estQueueMinutes, String estimatedStartAt,
                                        int fanoutMaxAllowed, boolean budgetHighWater,
                                        String message) {
}
