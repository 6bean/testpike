package com.sixbean.testpike.core.spec.api.dto;


public record SubscriptionScanResult(int scanned, int updated, int unchanged, int failed) {
}
