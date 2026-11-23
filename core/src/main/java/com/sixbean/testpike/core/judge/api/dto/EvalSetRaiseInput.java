package com.sixbean.testpike.core.judge.api.dto;

import java.util.List;


public record EvalSetRaiseInput(String setName, List<Long> sampleIds, Long createdBy) {
}
