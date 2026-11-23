package com.sixbean.testpike.core.document.api.dto;

/**
 * Per-document usage switches edit request ( package 2, PATCH
 * /internal/documents/{id}/usage-switches body). All three switches must
 * carry their intended values -- a missing field is refused with a
 * readable 40001 naming it (no silent half-write); revision = the row
 * optimistic-lock token the caller read.
 */
public record DocumentUsageSwitchesUpdate(Boolean useForExtraction, Boolean useInCasePrompt,
                                          Boolean useInGrounding, Integer revision) {
}
