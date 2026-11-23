package com.sixbean.testpike.admin.apitest.api.dto;


public record RunView(long id, long requestId, Long envId, int status, Long durationMs,
                      String assertDetail, String artifactRef, String errorMsg) {
}
