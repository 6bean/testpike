package com.sixbean.testpike.core.apitest.api.dto;


public record ApiRunInfo(long id, long requestId, Long envId, int status, Long durationMs,
                         String assertDetail, String artifactRef, String errorMsg) {
}
