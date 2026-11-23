package com.sixbean.testpike.core.apitest.api.dto;

/**
 * One dependency arrow of the preview card : the consuming step
 * reads pool key {@code key}, produced by request {@code producerRequestId}.
 * The producer is the nearest earlier extractor when one exists (that is the
 * value the hop actually consumes); a forward-only producer is still named so
 * the card can honestly flag the out-of-order reference.
 */
public record ApiRunSetPreviewDep(String key, long producerRequestId, boolean producerEarlier) {
}
