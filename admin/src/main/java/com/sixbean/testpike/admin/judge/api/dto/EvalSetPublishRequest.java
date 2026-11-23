package com.sixbean.testpike.admin.judge.api.dto;

import java.util.List;


public record EvalSetPublishRequest(String setName, List<EvalPublishSample> samples) {
}
