package com.sixbean.testpike.core.appauto.api.dto;

import java.util.List;


public record AppautoMatrixDispatchResult(long batchId, String batchNo, int createdTasks,
                                          List<Long> taskIds, String message) {
}
