package com.sixbean.testpike.core.cases.api.dto;

/**
 * Review lock status information (E10; contract 02: response body for POST/PUT /cases/{id}/review-lock).
 * <p>leaseRemainingMillis = remaining lease duration in milliseconds (the heartbeat response returns the new lease duration after renewal, referenced by the frontend 60s timer).
 *
 */
public record ReviewLockInfo(long caseId, long holderId, String holderName, long leaseRemainingMillis) {
}
