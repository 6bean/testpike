package com.sixbean.testpike.admin.cases.api.dto;


public record ReviewLockView(long caseId, long holderId, String holderName, long leaseRemainingMillis) {
}
