package com.sixbean.testpike.admin.deadletter.api.dto;

/**
 * Mirror of the core failure-class distribution row .
 * <p>The literal comes from the single-source classifier enum; the page grays out
 * ENTITY_GONE for replay per O3-a-2 without re-deriving the classification.
 */
public record DeadLetterClassCountView(String failureClass, long count) {
}
