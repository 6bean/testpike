package com.sixbean.testpike.core.deadletter.api.dto;

/**
 * One row of the failure-class distribution .
 * <p>The page shows this histogram to triage the backlog; ENTITY_GONE rows are
 * grayed out for replay per O3-a-2, so the class vocabulary comes from the
 * single-source classifier enum and is transported here as its literal name.
 *
 * @param failureClass one of the DeadLetterFailureClass names
 * @param count number of ledger rows of this class in the requested scope
 */
public record DeadLetterClassCount(String failureClass, long count) {
}
