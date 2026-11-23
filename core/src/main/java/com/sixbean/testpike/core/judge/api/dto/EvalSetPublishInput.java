package com.sixbean.testpike.core.judge.api.dto;

import java.util.List;


public record EvalSetPublishInput(String setName, List<SampleInput> samples, Long createdBy) {
}
