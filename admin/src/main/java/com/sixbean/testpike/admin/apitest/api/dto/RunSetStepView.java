package com.sixbean.testpike.admin.apitest.api.dto;

/**
 * Admin mirror of core ApiRunSetStep (one child run inside a collection run).
 * <p>protocol/assertCount feed the honest attribution line in the run-set
 * panel: a passed row says how many assertions it truly had.
 *
 * @param status 0=pending 1=passed 2=assert-failed 3=execution-error
 */
public record RunSetStepView(long runId, long requestId, int seqNo, String name,
                             String method, int status, Long durationMs, String errorMsg,
                             String protocol, int assertCount) {
}
