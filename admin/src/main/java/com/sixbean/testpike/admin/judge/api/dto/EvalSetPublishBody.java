package com.sixbean.testpike.admin.judge.api.dto;

import java.util.List;


public record EvalSetPublishBody(String setName, List<EvalPublishSample> samples, Long createdBy) {
}
