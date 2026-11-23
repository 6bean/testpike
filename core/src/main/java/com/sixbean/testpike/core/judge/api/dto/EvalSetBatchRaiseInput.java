package com.sixbean.testpike.core.judge.api.dto;

import java.util.List;


public record EvalSetBatchRaiseInput(String setName, List<Long> taskIds, Long createdBy) {
}
