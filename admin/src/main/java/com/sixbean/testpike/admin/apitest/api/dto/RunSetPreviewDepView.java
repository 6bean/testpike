package com.sixbean.testpike.admin.apitest.api.dto;

/**
 * Admin mirror of core ApiRunSetPreviewDep: one dependency arrow of the
 * launch-preview card. producerEarlier=false means the producing request sits
 * AFTER this step in tree order (the run would read an empty pool value — the
 * card flags it honestly instead of hiding the ordering problem).
 */
public record RunSetPreviewDepView(String key, long producerRequestId, boolean producerEarlier) {
}
