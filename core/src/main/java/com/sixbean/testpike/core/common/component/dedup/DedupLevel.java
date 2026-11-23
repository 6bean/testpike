package com.sixbean.testpike.core.common.component.dedup;

/**
 * Dedup gate verdict levels, single source for every create-type write path
 * that must not silently produce a duplicate entity.
 *
 * <p>EXACT: deterministic normalized key (project x entity x normalized title
 * or the entity's own business key) matched an existing row.
 * HIGH: fuzzy/semantic similarity at or above the high line - treated exactly
 * like EXACT (hard refusal to create, existing id named on the card).
 * MEDIUM: similarity between the mid line and the high line - top-N near
 * neighbours are presented with the three AI suggestions (merge / keep
 * separate / amend the existing one) and a near-miss audit trail is written.
 * PASS: nothing similar enough to mention; the create may proceed.
 */
public enum DedupLevel {
    EXACT,
    HIGH,
    MEDIUM,
    PASS
}
