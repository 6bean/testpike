package com.sixbean.testpike.core.spec.domain.component;

/**
 * Per-stub-key mock provenance verdict for contract-derived stubs.
 * <p>covered = a rule exists; llmOnly = every recorded non-null hit for the
 * matching stubs came from the AI fallback, so the inventory page must show
 * the "unverified" note instead of a plain covered badge (review R-8).
 */
public record MockProvenance(boolean llmOnly) {
}
