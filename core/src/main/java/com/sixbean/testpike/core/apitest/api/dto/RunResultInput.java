package com.sixbean.testpike.core.apitest.api.dto;


public record RunResultInput(Integer status, Long durationMs, String assertDetailJson,
                             String artifactRef, String errorMsg) {
}
